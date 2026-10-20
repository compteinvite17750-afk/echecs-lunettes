package fr.echecs.lunettes

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.graphics.Matrix
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EcranEtat(
    val statut: String = "Démarrage…",
    val dernierePhrase: String = "",
    val enregistrement: String = "",
    val estEnregistre: Boolean = false,
    val lunettes: String = "non connectées",
    val flux: String = "",
    val mouvement: Float = 0f,
    val modeleManquant: Boolean = false,
    val partie: String = "",
    val details: String = "",
    val image: Bitmap? = null,
    val enAnalyse: Boolean = false,
)

class Jeu(app: Application) : AndroidViewModel(app) {

  companion object {
    private const val TAG = "EchecsLunettes"
    private const val MOUVEMENT = 6f // différence moyenne (0-255) au-dessus de laquelle "ça bouge"
    private const val CALME_MS = 700L // temps sans mouvement avant de prendre la photo
    private const val VERIF_MS = 20000L // nouvelle vérification de temps en temps, même sans mouvement
    private const val TEMPS_MOTEUR = 700
  }

  private val _etat = MutableStateFlow(EcranEtat())
  val etat: StateFlow<EcranEtat> = _etat.asStateFlow()

  private val prefs = app.getSharedPreferences("partie", Context.MODE_PRIVATE)
  private var coups = ArrayList<String>()
  private var moi = 'w'
  private var coinsSauves: Array<DoubleArray>? = null
  private var rotMem = 0
  private var baseFen: String? = null // position de départ si la partie a été reprise en cours de route
  private var partieActive = false // true quand l'application suit une partie
  private var autoReprise = true // false après une fin de partie, jusqu'à ce qu'on revoie une position de départ
  private var lectureVue: String? = null // dernière lecture complète (il en faut 2 identiques)

  private class Lecture(
      val rot: Int,
      val im: Bitmap,
      val px: Array<DoubleArray>,
      val rc: Coins.Resultat,
      val r: Detecteur.Resultat,
      val actuel: Int,
      val notes: List<Partie.Note>,
      val qualite: Int,
      val brut: IntArray,
  )

  private var moteur: Moteur? = null
  private var detecteur: Detecteur? = null
  private var tts: TextToSpeech? = null

  // lunettes
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var flux: Stream? = null
  private var sdkPret = false

  // caméra du téléphone
  private var telephone = false
  private var fournisseur: ProcessCameraProvider? = null
  private var capturePhoto: ImageCapture? = null
  private var capteurOrientation: OrientationEventListener? = null
  private val execAnalyse = Executors.newSingleThreadExecutor()
  private val fileImages = Dispatchers.Default.limitedParallelism(1)

  // détection de mouvement
  private var precedent: FloatArray? = null
  private var aBouge = false
  private var dernierMouvement = 0L
  private var derniereCapture = 0L
  private var verifDemandeeA = 0L
  @Volatile private var dernierY: ByteArray? = null
  @Volatile private var dernierYw = 0
  @Volatile private var dernierYh = 0

  // analyse
  private val occupe = AtomicBoolean(false)
  private var coupEnAttente: String? = null
  private var incertain = 0
  private var attendMonCoup = false
  private var occAvant: IntArray? = null
  private var attenteVaine = 0

  init {
    charger()
    tts =
        TextToSpeech(app) { st ->
          if (st == TextToSpeech.SUCCESS) {
            tts?.language = Locale.FRANCE
            tts?.setSpeechRate(0.85f)
          }
        }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val chemin = app.applicationInfo.nativeLibraryDir + "/libstockfish.so"
        if (!File(chemin).exists()) {
          statut("ERREUR : Stockfish manquant dans l'application.")
        } else {
          moteur = Moteur(chemin)
        }
      } catch (e: Exception) {
        statut("ERREUR Stockfish : ${e.message}")
      }
      chargerModele()
      majPartie()
      if (moteur != null && detecteur != null) statut("Prêt.")
    }
  }

  // ---------------- sauvegarde ----------------
  private fun charger() {
    coups = ArrayList(prefs.getString("coups", "")!!.split(" ").filter { it.isNotBlank() })
    moi = prefs.getString("moi", "w")!![0]
    baseFen = prefs.getString("base", "")!!.ifBlank { null }
    Partie.style = prefs.getInt("style_cases", 0)
    coinsSauves =
        prefs.getString("coins", null)?.let { s ->
          val v = s.split(",").mapNotNull { it.toDoubleOrNull() }
          if (v.size == 8) Array(4) { doubleArrayOf(v[2 * it], v[2 * it + 1]) } else null
        }
  }

  private fun sauver() {
    prefs
        .edit()
        .putString("coups", coups.joinToString(" "))
        .putString("moi", moi.toString())
        .putString("base", baseFen ?: "")
        .putString("coins", coinsSauves?.joinToString(",") { "${it[0]},${it[1]}" })
        .apply()
  }

  private fun positionDe(l: List<String>): String {
    val base = baseFen?.let { "position fen $it" } ?: "position startpos"
    return if (l.isEmpty()) base else "$base moves " + l.joinToString(" ")
  }

  private fun position(): String = positionDe(coups)

  // ---------------- modèle ----------------
  private fun chargerModele() {
    val app = getApplication<Application>()
    val octets: ByteArray? =
        try {
          app.assets.open("best.onnx").use { it.readBytes() }
        } catch (e: Exception) {
          val f = File(app.filesDir, "best.onnx")
          if (f.exists()) f.readBytes() else null
        }
    if (octets == null) {
      _etat.update { it.copy(modeleManquant = true) }
      statut("Il manque le fichier best.onnx : appuie sur « Charger le modèle ».")
      return
    }
    try {
      detecteur = Detecteur(octets)
      _etat.update { it.copy(modeleManquant = false) }
    } catch (e: Exception) {
      statut("ERREUR modèle : ${e.message}")
    }
  }

  fun importerModele(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val app = getApplication<Application>()
        app.contentResolver.openInputStream(uri)!!.use { inp ->
          File(app.filesDir, "best.onnx").outputStream().use { inp.copyTo(it) }
        }
        chargerModele()
        if (detecteur != null) statut("Modèle chargé.")
      } catch (e: Exception) {
        statut("ERREUR copie du modèle : ${e.message}")
      }
    }
  }

  // ---------------- voix et affichage ----------------
  private var dernierDit = ""

  /** dit = ce qui est prononcé ("|" = petite pause), ecran = ce qui est écrit. */
  private fun parle(dit: String, ecran: String = dit.replace("|", " ")) {
    dernierDit = dit
    _etat.update { it.copy(dernierePhrase = ecran) }
    val t = tts ?: return
    val parts = dit.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    parts.forEachIndexed { i, s ->
      if (i > 0) t.playSilentUtterance(700, TextToSpeech.QUEUE_ADD, "pause$i")
      t.speak(s, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "phrase$i")
    }
  }

  private fun statut(t: String) {
    Log.i(TAG, t)
    _etat.update { it.copy(statut = t) }
  }

  private fun majPartie() {
    val m = moteur ?: return
    try {
      val e = m.etat(position())
      val b = Partie.depuisFen(e[0])
      val sb = StringBuilder()
      for (r in 0 until 8) {
        sb.append(8 - r).append("  ")
        for (c in 0 until 8) sb.append(b[r * 8 + c]).append(' ')
        sb.append('\n')
      }
      sb.append("   a b c d e f g h\n")
      sb.append(if (moi == 'w') "Tu joues les blancs" else "Tu joues les noirs")
      sb.append(" — ").append(coups.size).append(" demi-coups")
      _etat.update { it.copy(partie = sb.toString()) }
    } catch (e: Exception) {
      Log.e(TAG, "majPartie", e)
    }
  }

  // ---------------- partie ----------------
  fun nouvellePartie(blancs: Boolean) {
    viewModelScope.launch(Dispatchers.Default) {
      coups.clear()
      baseFen = null
      partieActive = true
      autoReprise = true
      attendMonCoup = false
      moi = if (blancs) 'w' else 'b'
      coupEnAttente = null
      incertain = 0
      sauver()
      if (blancs) proposer("Nouvelle partie. ")
      else {
        parle("Nouvelle partie. Tu as les noirs. J'attends le coup des blancs.")
        statut("J'attends le coup des blancs.")
      }
      majPartie()
    }
  }

  /** Annule le dernier coup de l'adversaire (et ma réponse), si je me suis trompé. */
  fun annuler() {
    viewModelScope.launch(Dispatchers.Default) {
      val m = moteur ?: return@launch
      val trait = m.etat(position())[0].split(" ")[1][0]
      // Si c'est au tour de l'adversaire, le dernier coup est le mien : on enlève les 2 derniers.
      val n = if (trait != moi) 2 else 1
      if (coups.size >= n && coups.isNotEmpty()) {
        repeat(n) { coups.removeAt(coups.size - 1) }
        sauver()
        coupEnAttente = null
        attendMonCoup = false
        parle("Coup annulé. Remets les pièces comme avant le coup de l'adversaire.")
        statut("Dernier coup adverse annulé.")
      }
      majPartie()
    }
  }

  /** Coup de l'adversaire tapé à la main (ex. e7e5). */
  fun coupManuel(texte: String) {
    viewModelScope.launch(Dispatchers.Default) {
      val m = moteur ?: return@launch
      val uci = texte.trim().lowercase()
      val legaux = m.coupsLegaux(position())
      if (uci !in legaux) {
        statut("« $texte » n'est pas un coup légal ici. Exemple : e7e5")
        return@launch
      }
      val b = Partie.depuisFen(m.etat(position())[0])
      val phrase = Partie.decrire(b, uci)
      coups.add(uci)
      sauver()
      proposer("Adversaire : $phrase. ")
      majPartie()
    }
  }

  /** Change la façon de dire les cases et fait entendre un exemple. */
  fun changerStyle() {
    val st = (Partie.style + 1) % 4
    Partie.style = st
    prefs.edit().putInt("style_cases", st).apply()
    val b = Partie.depuisFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    val essai = Partie.dire(b, "e2e4") + ".|" + Partie.dire(b, "g1f3") + ".|" + Partie.dire(b, "f1b5") + "."
    statut("Façon de dire les cases : n° ${st + 1} sur 4.")
    parle(essai)
  }

  fun repeter() {
    val p = dernierDit
    if (p.isNotBlank()) parle(p, _etat.value.dernierePhrase)
  }

  /** Demande à Stockfish mon coup, l'annonce et l'ajoute à la partie. */
  private fun proposer(prefixe: String, prefixeDit: String = "") {
    val m = moteur ?: return
    val pos = position()
    val legaux = m.coupsLegaux(pos)
    if (legaux.isEmpty()) {
      val echec = m.etat(pos)[1] == "1"
      parle(prefixe + if (echec) "Échec et mat. La partie est finie." else "Pat. Match nul.")
      statut("Partie terminée.")
      partieActive = false
      autoReprise = false
      return
    }
    val coup = m.meilleurCoup(pos, TEMPS_MOTEUR) ?: return
    val b = Partie.depuisFen(m.etat(pos)[0])
    val phrase = Partie.decrire(b, coup)
    val dite = Partie.dire(b, coup)
    occAvant = Partie.occupation(b)
    attendMonCoup = true
    attenteVaine = 0
    coups.add(coup)
    sauver()
    val pos2 = position()
    val fini = m.coupsLegaux(pos2).isEmpty()
    val echec = m.etat(pos2)[1] == "1"
    if (fini) {
      partieActive = false
      autoReprise = false
    }
    val suite =
        when {
          fini && echec -> " Échec et mat, tu as gagné !"
          fini -> " Pat, match nul."
          echec -> " Échec."
          else -> ""
        }
    parle("$prefixeDit$dite.$suite", "${prefixe}Joue : $phrase.$suite")
    statut("TON COUP : $phrase.$suite")
    majPartie()
  }

  private fun partieEnCours(): Boolean {
    val m = moteur ?: return false
    if (!partieActive) return true // on cherche encore la partie
    return try {
      val pos = position()
      val trait = m.etat(pos)[0].split(" ")[1][0]
      trait != moi && m.coupsLegaux(pos).isNotEmpty()
    } catch (e: Exception) {
      false
    }
  }

  // ---------------- analyse d'une image ----------------
  /** Photo choisie dans la galerie (pour tester sans les lunettes). */
  fun analyserFichier(uri: Uri) {
    if (!occupe.compareAndSet(false, true)) return
    viewModelScope.launch(Dispatchers.Default) {
      try {
        val octets =
            getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bmp = Detecteur.decode(octets)
        if (bmp == null) statut("Image illisible.") else analyser(bmp)
      } catch (e: Exception) {
        statut("ERREUR : ${e.message}")
      } finally {
        occupe.set(false)
      }
    }
  }

  private fun analyser(img: Bitmap) {
    val m = moteur ?: return statut("Stockfish n'est pas prêt.")
    val det = detecteur ?: return statut("Le modèle n'est pas chargé.")
    _etat.update { it.copy(enAnalyse = true) }
    try {
      if (!partieActive) {
        demarrageAuto(img, det, m)
        return
      }
      val pos = position()
      val e = m.etat(pos)
      val trait = e[0].split(" ")[1][0]
      val legaux = m.coupsLegaux(pos)
      if (legaux.isEmpty()) return statut("La partie est terminée. Lance une nouvelle partie.")
      if (trait == moi) {
        // c'est à moi : on n'a pas encore proposé de coup (ex. après une annulation)
        proposer("")
        return
      }

      val b = Partie.depuisFen(e[0])
      // On ne sait pas dans quel sens le téléphone est tenu : on essaie les 4 rotations
      // de l'image et on garde celle où les pièces collent le mieux avec la partie.
      var meil: Lecture? = null
      val candidats = ArrayList<IntArray>()
      candidats.add(Partie.occupation(b))
      for (m1 in legaux) candidats.add(Partie.occupation(Partie.apres(b, m1)))
      val ordre = listOf(rotMem) + listOf(0, 90, 180, 270).filter { it != rotMem }
      for (rot in ordre) {
        val im =
            if (rot == 0) img
            else Bitmap.createBitmap(img, 0, 0, img.width, img.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val rc1 = Coins.trouve(Detecteur.gris500(im), if (rot == rotMem) coinsSauves else null)
        if (rc1.score < Coins.SEUIL_OK) continue
        var px1 = Array(4) { doubleArrayOf(rc1.coins[it][0] * im.width, rc1.coins[it][1] * im.height) }
        if (moi == 'b') px1 = arrayOf(px1[2], px1[3], px1[0], px1[1])
        val r0 = det.detecte(im, px1) ?: continue
        // recalage fin des coins grâce aux pièces (la position attendue est connue)
        px1 = Detecteur.raffine(r0.pts, px1, candidats)
        val r1 = Detecteur.Resultat(Detecteur.observer(r0.pts, px1) ?: r0.obs, r0.nbPieces, r0.pts)
        val act1 = Partie.ecart(r1.obs, Partie.occupation(b))
        val notes1 = Partie.classe(b, legaux, r1.obs)
        val q = minOf(act1, notes1[0].ecart)
        if (meil == null || q < meil.qualite) meil = Lecture(rot, im, px1, rc1, r1, act1, notes1, q, r0.obs)
        if (q <= 6) break
      }
      if (meil == null) {
        _etat.update { it.copy(image = Detecteur.dessine(img, null, null)) }
        statut("Je ne vois pas bien l'échiquier.")
        return
      }
      val choix: Lecture = meil
      val rc = choix.rc
      val px = choix.px
      val r = choix.r
      val actuel = choix.actuel
      val notes = choix.notes
      val imgOk = choix.im
      val meilleur = notes[0]
      val marge = Partie.marge(notes)
      _etat.update {
        it.copy(
            image = Detecteur.dessine(imgOk, px, r.obs),
            details =
                "pièces vues ${r.nbPieces} · écart actuel $actuel · meilleur ${meilleur.coup} (${meilleur.ecart})" +
                    " · marge $marge · plateau %.2f · rotation ${choix.rot}".format(rc.score),
        )
      }

      if (attendMonCoup) {
        val av = occAvant
        if (av != null && actuel < Partie.ecart(r.obs, av)) attendMonCoup = false // il a joué son coup
      }
      val plausible = meilleur.ecart <= 4 && marge >= 2 && actuel - meilleur.ecart >= 2
      if (!plausible) {
        coupEnAttente = null
        if (attendMonCoup) {
          // On attend que le joueur joue son coup : on ne se plaint pas pendant qu'il joue.
          val av = occAvant
          val eAv = if (av != null) Partie.ecart(r.obs, av) else 99
          if (eAv > 3 && actuel > 3) {
            attenteVaine++
            if (attenteVaine >= 4 && resynchroniser(r.obs, choix.brut, meilleur.ecart)) {
              attenteVaine = 0
              attendMonCoup = false
              return
            }
          } else {
            attenteVaine = 0
          }
          statut("J'attends que tu joues ton coup.")
          return
        }
        if (actuel >= 4) {
          incertain++
          if (incertain >= 2 && resynchroniser(r.obs, choix.brut, meilleur.ecart)) {
            incertain = 0
            return
          }
          if (incertain >= 5 && relireTout(choix)) {
            incertain = 0
            return
          }
          statut("Je ne reconnais pas le coup (essai $incertain).")
          if (incertain == 4) parle("Je ne comprends pas le coup de l'adversaire. Vérifie que ses pièces sont bien au milieu des cases.")
          verifDemandeeA = SystemClock.elapsedRealtime() + 1500
        } else {
          incertain = 0
          statut("Pas de nouveau coup. J'attends l'adversaire.")
        }
        return
      }
      attendMonCoup = false
      incertain = 0
      coinsSauves = rc.coins
      rotMem = choix.rot
      val fort = (marge >= 3 && actuel - meilleur.ecart >= 4) || (meilleur.ecart <= 1 && marge >= 2 && actuel - meilleur.ecart >= 2)
      if (!fort && coupEnAttente != meilleur.coup) {
        coupEnAttente = meilleur.coup
        statut("Coup probable : ${Partie.decrire(b, meilleur.coup)}. Je vérifie…")
        verifDemandeeA = SystemClock.elapsedRealtime() + 1500
        return
      }
      coupEnAttente = null
      val phrase = Partie.decrire(b, meilleur.coup)
      coups.add(meilleur.coup)
      sauver()
      proposer("Adversaire : $phrase. ")
    } catch (ex: Exception) {
      Log.e(TAG, "analyse", ex)
      statut("ERREUR analyse : ${ex.message}")
    } finally {
      _etat.update { it.copy(enAnalyse = false) }
    }
  }

  // ---------------- démarrage et reprise automatiques ----------------
  private class Cas(
      val rot: Int,
      val rc: Coins.Resultat,
      val px: Array<DoubleArray>,
      val r: Detecteur.Resultat,
  )

  private fun tourner(img: Bitmap, rot: Int): Bitmap =
      if (rot == 0) img
      else Bitmap.createBitmap(img, 0, 0, img.width, img.height, Matrix().apply { postRotate(rot.toFloat()) }, true)

  /** Quand aucune partie n'est suivie : reconnaît une nouvelle partie, reprend la partie en cours, ou relit tout l'échiquier. */
  private fun demarrageAuto(img: Bitmap, det: Detecteur, m: Moteur) {
    val occDepart = Partie.occupation(Partie.depuisFen(Lecteur.FEN_DEPART))
    val occDepartInv = Partie.occupation(Lecteur.tourne180(Partie.depuisFen(Lecteur.FEN_DEPART)))
    val candDepart = listOf(occDepart, occDepartInv)
    val cas = ArrayList<Cas>()
    val ordre = listOf(rotMem) + listOf(0, 90, 180, 270).filter { it != rotMem }
    for (rot in ordre) {
      val im = tourner(img, rot)
      val rc1 = Coins.trouve(Detecteur.gris500(im), if (rot == rotMem) coinsSauves else null)
      if (rc1.score < Coins.SEUIL_OK) continue
      val px1 = Array(4) { doubleArrayOf(rc1.coins[it][0] * im.width, rc1.coins[it][1] * im.height) }
      val r0 = det.detecte(im, px1) ?: continue
      val pxR = Detecteur.raffine(r0.pts, px1, candDepart)
      val o = Detecteur.observer(r0.pts, pxR) ?: continue
      val eW = Partie.ecart(o, occDepart)
      val eB = Partie.ecart(o, occDepartInv)
      cas.add(Cas(rot, rc1, pxR, Detecteur.Resultat(o, r0.nbPieces, r0.pts)))
      if (minOf(eW, eB) <= 3) {
        // nouvelle partie : les pièces sont en position de départ
        _etat.update { it.copy(image = Detecteur.dessine(im, pxR, o)) }
        moi = if (eW <= eB) 'w' else 'b'
        baseFen = null
        coups.clear()
        rotMem = rot
        coinsSauves = rc1.coins
        partieActive = true
        autoReprise = true
        attendMonCoup = false
        coupEnAttente = null
        incertain = 0
        lectureVue = null
        sauver()
        majPartie()
        if (moi == 'w') proposer("Nouvelle partie reconnue. ", "Nouvelle partie. ")
        else {
          statut("Nouvelle partie reconnue : tu as les noirs. J'attends le coup des blancs.")
          parle("Nouvelle partie. Tu as les noirs. J'attends le coup des blancs.")
        }
        return
      }
    }
    if (cas.isEmpty()) {
      statut("Je cherche l'échiquier…")
      return
    }
    if (!autoReprise) {
      statut("Partie terminée. Remets les pièces en place pour une nouvelle partie.")
      return
    }

    // La partie sauvegardée correspond-elle à ce qu'on voit ? Alors on la reprend.
    if (coups.isNotEmpty() || baseFen != null) {
      val pos = position()
      val b = Partie.depuisFen(m.etat(pos)[0])
      val legaux = m.coupsLegaux(pos)
      if (legaux.isNotEmpty()) {
        val cands = ArrayList<IntArray>()
        cands.add(Partie.occupation(b))
        for (mv in legaux) cands.add(Partie.occupation(Partie.apres(b, mv)))
        for (c in cas) {
          var pxT = c.px
          if (moi == 'b') pxT = arrayOf(pxT[2], pxT[3], pxT[0], pxT[1])
          pxT = Detecteur.raffine(c.r.pts, pxT, cands)
          val o = Detecteur.observer(c.r.pts, pxT) ?: continue
          val ec = Partie.ecart(o, cands[0])
          val ne = Partie.classe(b, legaux, o)[0].ecart
          if (minOf(ec, ne) <= 4) {
            rotMem = c.rot
            coinsSauves = c.rc.coins
            partieActive = true
            incertain = 0
            lectureVue = null
            statut("Partie reprise.")
            parle("Partie reprise.")
            majPartie()
            return
          }
        }
      }
    }

    // Sinon : on relit toutes les pièces de l'échiquier.
    var meilleurPlateau: CharArray? = null
    var meilleurScore = Int.MIN_VALUE
    var joueur = 'w'
    var rotChoisie = 0
    var rcChoisi: Coins.Resultat? = null
    for (c in cas) {
      val lu = Detecteur.lire(c.r.pts, c.px)
      for (inverse in listOf(false, true)) {
        val plateau = if (inverse) Lecteur.tourne180(lu) else lu
        if (!Lecteur.valide(plateau)) continue
        val sc = Lecteur.scoreOrientation(plateau)
        if (sc > meilleurScore) {
          meilleurScore = sc
          meilleurPlateau = plateau
          joueur = if (inverse) 'b' else 'w'
          rotChoisie = c.rot
          rcChoisi = c.rc
        }
      }
    }
    val plateau = meilleurPlateau
    if (plateau == null || meilleurScore < 6) {
      lectureVue = null
      statut("Je n'arrive pas à reconnaître toutes les pièces.")
      return
    }
    if (adopter(plateau, joueur, "Partie reconnue. ")) {
      rotMem = rotChoisie
      coinsSauves = rcChoisi?.coins
      sauver()
    }
  }

  /** Quand le suivi est perdu : on relit tout l'échiquier pour se recaler. */
  private fun relireTout(choix: Lecture): Boolean {
    // les coins de la lecture sont déjà retournés quand je joue les noirs : le plateau est dans le bon sens
    val lu = Detecteur.lire(choix.r.pts, choix.px)
    return adopter(lu, moi, "Je me suis recalé sur l'échiquier. ")
  }

  /** Adopte une position lue sur l'échiquier (2 lectures identiques de suite) et donne mon coup. */
  private fun adopter(plateau: CharArray, joueur: Char, intro: String): Boolean {
    val m = moteur ?: return false
    if (!Lecteur.valide(plateau)) {
      lectureVue = null
      statut("Je n'arrive pas à reconnaître toutes les pièces.")
      return false
    }
    val fen = Lecteur.fen(plateau, joueur)
    if (lectureVue != fen) {
      lectureVue = fen
      statut("Je lis l'échiquier…")
      return false
    }
    lectureVue = null
    val ancienneBase = baseFen
    val anciensCoups = ArrayList(coups)
    baseFen = fen
    coups.clear()
    val ok =
        try {
          val autre = Lecteur.fen(plateau, if (joueur == 'w') 'b' else 'w')
          val horsJeu = m.etat("position fen $autre")[1] == "1" // le camp qui ne joue pas ne peut pas être en échec
          !horsJeu && m.coupsLegaux(position()).isNotEmpty()
        } catch (e: Exception) {
          false
        }
    if (!ok) {
      baseFen = ancienneBase
      coups.clear()
      coups.addAll(anciensCoups)
      statut("La position lue n'est pas possible aux échecs. Je réessaie.")
      return false
    }
    moi = joueur
    partieActive = true
    autoReprise = true
    attendMonCoup = false
    coupEnAttente = null
    incertain = 0
    sauver()
    majPartie()
    proposer(intro, intro)
    return true
  }

  /**
   * Le joueur a peut-être joué autre chose que le coup conseillé (et l'adversaire a peut-être déjà répondu).
   * On essaie tous ces cas et on garde celui qui colle le mieux avec ce que la caméra voit.
   */
  private fun resynchroniser(obs: IntArray, brut: IntArray, ecartNormal: Int): Boolean {
    val m = moteur ?: return false
    if (coups.isEmpty()) return false
    val dernier = coups.last()
    val avant = ArrayList<String>(coups.subList(0, coups.size - 1))
    val bAvant = Partie.depuisFen(m.etat(positionDe(avant))[0])
    class Cand(val ecart: Int, val seq: List<String>, val occ: IntArray)
    val cands = ArrayList<Cand>()
    fun note(b: CharArray, seq: List<String>) {
      val occ = Partie.occupation(b)
      cands.add(Cand(minOf(Partie.ecart(obs, occ), Partie.ecart(brut, occ)), seq, occ))
    }
    for (alt in m.coupsLegaux(positionDe(avant))) {
      if (alt == dernier) continue
      val bAlt = Partie.apres(bAvant, alt)
      note(bAlt, listOf(alt))
      for (rep in m.coupsLegaux(positionDe(avant + alt))) note(Partie.apres(bAlt, rep), listOf(alt, rep))
    }
    cands.sortBy { it.ecart }
    val best = cands.firstOrNull() ?: return false
    val autre = cands.firstOrNull { !it.occ.contentEquals(best.occ) }
    val marge = (autre?.ecart ?: 99) - best.ecart
    if (best.ecart > 2 || marge < 2 || best.ecart >= ecartNormal) return false

    val seq = best.seq
    val phraseMoi = Partie.decrire(bAvant, seq[0])
    coups.clear()
    coups.addAll(avant)
    coups.add(seq[0])
    coupEnAttente = null
    attendMonCoup = false
    if (seq.size == 1) {
      sauver()
      majPartie()
      statut("Tu as joué : $phraseMoi. J'attends l'adversaire.")
      parle("Tu n'as pas joué le coup prévu. Je m'adapte. J'attends l'adversaire.")
      return true
    }
    val bAlt = Partie.apres(bAvant, seq[0])
    val phraseOpp = Partie.decrire(bAlt, seq[1])
    coups.add(seq[1])
    sauver()
    proposer("Tu as joué : $phraseMoi. Adversaire : $phraseOpp. ", "Je m'adapte. | ")
    return true
  }

  // ---------------- lunettes ----------------
  fun sdkInitialise() {
    if (sdkPret) return
    sdkPret = true
    viewModelScope.launch {
      Wearables.registrationState.collect { st ->
        _etat.update {
          it.copy(enregistrement = st.toString(), estEnregistre = st == RegistrationState.REGISTERED)
        }
      }
    }
  }

  fun demarrerCamera(demande: suspend (Permission) -> PermissionStatus) {
    if (session != null) return
    viewModelScope.launch {
      var ok = false
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { st -> ok = st == PermissionStatus.Granted }
          .onFailure { err, _ -> statut("Permission : ${err.description}") }
      if (!ok) ok = demande(Permission.CAMERA) == PermissionStatus.Granted
      if (!ok) {
        statut("La caméra des lunettes n'est pas autorisée.")
        return@launch
      }
      ouvrirSession()
    }
  }

  private fun ouvrirSession() {
    Wearables.createSession(AutoDeviceSelector())
        .onSuccess { s ->
          session = s
          viewModelScope.launch {
            s.errors.collect { err -> statut("Lunettes : ${err.description}") }
          }
          viewModelScope.launch {
            s.state.collect { st ->
              _etat.update { it.copy(lunettes = st.toString()) }
              if (st == DeviceSessionState.STARTED && camera == null) ajouterCamera(s)
              if (st == DeviceSessionState.STOPPED) {
                session = null
                camera = null
                flux = null
              }
            }
          }
          s.start()
        }
        .onFailure { err, _ -> statut("Connexion impossible : ${err.description}") }
  }

  private fun ajouterCamera(s: DeviceSession) {
    s.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 7))
        .onSuccess { cam ->
          camera = cam
          val st = cam.stream
          flux = st
          viewModelScope.launch(fileImages) { st.videoStream.collect { surImage(it) } }
          viewModelScope.launch { st.state.collect { v -> _etat.update { it.copy(flux = v.toString()) } } }
          viewModelScope.launch { st.errorStream.collect { err -> statut("Vidéo : ${err.description}") } }
          st.start().onFailure { err, _ -> statut("Vidéo impossible : ${err.description}") }
          statut("Caméra des lunettes démarrée. Regarde l'échiquier.")
        }
        .onFailure { err, _ -> statut("Caméra impossible : ${err.description}") }
  }

  fun arreterCamera() {
    session?.stop()
  }

  private fun surImage(f: VideoFrame) {
    if (f.isCompressed) return
    val w = f.width
    val h = f.height
    val buf = f.buffer.duplicate()
    if (w <= 0 || h <= 0 || buf.remaining() < w * h) return
    val y = ByteArray(w * h)
    buf.get(y, 0, w * h)
    surY(y, w, h)
  }

  /** Reçoit le plan de luminosité d'une image (lunettes ou téléphone) et décide s'il faut analyser. */
  private fun surY(y: ByteArray, w: Int, h: Int) {
    dernierY = y
    dernierYw = w
    dernierYh = h

    // mini image 32x32 pour mesurer le mouvement
    val n = 32
    val mini = FloatArray(n * n)
    for (j in 0 until n) for (i in 0 until n) {
      mini[j * n + i] = (y[(j * h / n) * w + i * w / n].toInt() and 255).toFloat()
    }
    val p = precedent
    precedent = mini
    if (p == null) return
    var d = 0f
    for (k in mini.indices) d += abs(mini[k] - p[k])
    d /= mini.size
    _etat.update { it.copy(mouvement = d) }

    val maintenant = SystemClock.elapsedRealtime()
    if (d > MOUVEMENT) {
      aBouge = true
      dernierMouvement = maintenant
    }
    val calme = maintenant - dernierMouvement > CALME_MS
    if (occupe.get()) return
    val verif = verifDemandeeA in 1..maintenant
    val periodique = maintenant - derniereCapture > (if (telephone) 6000L else VERIF_MS)
    // Téléphone tenu à la main : l'image ne se calme jamais, on regarde quand même de temps en temps.
    val force = telephone && !calme && maintenant - derniereCapture > 10000L
    if (((calme && (aBouge || verif || periodique)) || force) && partieEnCours()) {
      aBouge = false
      verifDemandeeA = 0
      capturer()
    }
  }

  // ---------------- caméra du téléphone ----------------
  fun demarrerTelephone(owner: LifecycleOwner, vue: PreviewView) {
    val ctx = getApplication<Application>()
    val futur = ProcessCameraProvider.getInstance(ctx)
    futur.addListener(
        {
          try {
            val prov = futur.get()
            fournisseur = prov
            val apercu = Preview.Builder().build().also { it.setSurfaceProvider(vue.surfaceProvider) }
            val analyse =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            analyse.setAnalyzer(execAnalyse) { img -> analyserTel(img) }
            val photo =
                ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            capturePhoto = photo
            // la photo suit la position réelle du téléphone (vertical ou horizontal)
            capteurOrientation?.disable()
            capteurOrientation =
                object : OrientationEventListener(ctx) {
                  override fun onOrientationChanged(deg: Int) {
                    if (deg == ORIENTATION_UNKNOWN) return
                    val rot =
                        when {
                          deg in 45..134 -> Surface.ROTATION_270
                          deg in 135..224 -> Surface.ROTATION_180
                          deg in 225..314 -> Surface.ROTATION_90
                          else -> Surface.ROTATION_0
                        }
                    capturePhoto?.targetRotation = rot
                  }
                }.also { it.enable() }
            prov.unbindAll()
            prov.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, apercu, analyse, photo)
            telephone = true
            precedent = null
            statut("Caméra du téléphone démarrée. Pose le téléphone face à l'échiquier.")
            _etat.update { it.copy(flux = "téléphone") }
          } catch (e: Exception) {
            Log.e(TAG, "caméra téléphone", e)
            statut("ERREUR caméra du téléphone : ${e.message}")
          }
        },
        ContextCompat.getMainExecutor(ctx),
    )
  }

  fun arreterTelephone() {
    capteurOrientation?.disable()
    fournisseur?.unbindAll()
    telephone = false
    capturePhoto = null
    _etat.update { it.copy(flux = "") }
    statut("Caméra du téléphone arrêtée.")
  }

  private fun analyserTel(img: ImageProxy) {
    try {
      val plan = img.planes[0]
      val w = img.width
      val h = img.height
      val stride = plan.rowStride
      val buf = plan.buffer
      val y = ByteArray(w * h)
      for (r in 0 until h) {
        buf.position(r * stride)
        buf.get(y, r * w, w)
      }
      surY(y, w, h)
    } catch (e: Exception) {
      Log.w(TAG, "analyse tel", e)
    } finally {
      img.close()
    }
  }

  /** Photo nette avec la caméra du téléphone, remise à l'endroit. */
  private suspend fun photoTelephone(): Bitmap? {
    val cap = capturePhoto ?: return null
    val brut: Pair<ByteArray, Int>? =
        suspendCancellableCoroutine { c ->
          cap.takePicture(
              ContextCompat.getMainExecutor(getApplication<Application>()),
              object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                  try {
                    val b = image.planes[0].buffer
                    val octets = ByteArray(b.remaining())
                    b.get(octets)
                    val rot = image.imageInfo.rotationDegrees
                    image.close()
                    c.resume(Pair(octets, rot))
                  } catch (e: Exception) {
                    image.close()
                    c.resume(null)
                  }
                }

                override fun onError(exception: ImageCaptureException) {
                  Log.w(TAG, "photo tel", exception)
                  c.resume(null)
                }
              },
          )
        }
    if (brut == null) return null
    return withContext(Dispatchers.Default) {
      val bmp = Detecteur.decodeBrut(brut.first) ?: return@withContext null
      if (brut.second == 0) bmp
      else {
        val m = Matrix().apply { postRotate(brut.second.toFloat()) }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
      }
    }
  }

  /** Prend une photo nette avec les lunettes et l'analyse. */
  fun capturer() {
    if (!occupe.compareAndSet(false, true)) return
    viewModelScope.launch {
      try {
        var img: Bitmap? = null
        if (telephone) img = photoTelephone()
        val st = if (telephone) null else flux
        if (st != null) {
          var photo: PhotoData? = null
          st.capturePhoto()
              .onSuccess { photo = it }
              .onFailure { err, _ -> Log.w(TAG, "photo : ${err.description}") }
          val ph = photo
          if (ph != null) {
            img =
                withContext(Dispatchers.Default) {
                  when (ph) {
                    is PhotoData.Bitmap -> ph.bitmap
                    is PhotoData.HEIC -> {
                      val bb = ph.data.duplicate().apply { rewind() }
                      val octets = ByteArray(bb.remaining())
                      bb.get(octets)
                      Detecteur.decode(octets)
                    }
                  }
                }
          }
        }
        if (img == null && !telephone) {
          val y = dernierY
          if (y != null) img = Detecteur.depuisY(y, dernierYw, dernierYh)
        }
        if (img == null) {
          statut("Pas d'image. Démarre la caméra (lunettes ou téléphone).")
        } else {
          val i = img
          withContext(Dispatchers.Default) { analyser(i) }
        }
      } catch (e: Exception) {
        Log.e(TAG, "capture", e)
        statut("ERREUR capture : ${e.message}")
      } finally {
        derniereCapture = SystemClock.elapsedRealtime()
        occupe.set(false)
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    session?.stop()
    moteur?.ferme()
    tts?.shutdown()
  }
}
