package fr.echecs.lunettes

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
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
    private const val CALME_MS = 150L // temps sans mouvement avant de prendre la photo
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

  // photos gardées pour réentraîner la reconnaissance (dossier Téléchargements/echecs-photos)
  private var dernierEnreg = 0L
  private var dernierCle = -1
  private var dernierePropA = 0L
  private var petitEcart = 0
  private var forceRelire = false

  // journal de ce que fait l'appli (Téléchargements/echecs-photos/echecs-journal.txt) : sert à comprendre les erreurs
  private val journal = ArrayList<String>()
  private var dernierJournal = 0L

  private fun noter(s: String) {
    try {
      val h = java.text.SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(java.util.Date())
      synchronized(journal) {
        journal.add("$h $s")
        while (journal.size > 3000) journal.removeAt(0)
      }
      val now = SystemClock.elapsedRealtime()
      if (now - dernierJournal > 4000) {
        dernierJournal = now
        ecrireJournal()
      }
    } catch (e: Exception) {
    }
  }

  private fun ecrireJournal() {
    try {
      val res = getApplication<Application>().contentResolver
      val nom = "echecs-journal.txt"
      val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
      var trouve: Uri? = null
      res.query(
              col,
              arrayOf(MediaStore.MediaColumns._ID),
              MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
              arrayOf(nom, "Download/echecs-photos%"),
              null,
          )
          ?.use { c -> if (c.moveToFirst()) trouve = ContentUris.withAppendedId(col, c.getLong(0)) }
      var uri: Uri? = trouve
      if (uri == null) {
        val cv = ContentValues()
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nom)
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/echecs-photos")
        uri = res.insert(col, cv)
      }
      val u = uri ?: return
      val texte = synchronized(journal) { journal.joinToString("\n") }
      res.openOutputStream(u, "wt")?.use { it.write(texte.toByteArray()) }
    } catch (e: Exception) {
      Log.w("Jeu", "journal impossible : " + e.message)
    }
  }
  private var relectures = 0
  private var nbEnreg = -1

  /** Garde une image où la position lue colle avec la partie suivie, avec la position exacte et les coins du plateau. */
  private fun enregistrer(img: Bitmap, fen: String, coins: Array<DoubleArray>, cle: Int) {
    try {
      val now = SystemClock.elapsedRealtime()
      // une seule photo par position de la partie (après chaque coup, le tien comme celui de l'adversaire)
      if (cle == dernierCle || now - dernierEnreg < 1500) return
      val res = getApplication<Application>().contentResolver
      if (nbEnreg < 0 || nbEnreg >= 400) {
        // on compte les photos réellement présentes dans le dossier (si tu les supprimes, ça repart)
        val cur =
            res.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.MIME_TYPE + " = ?",
                arrayOf("Download/echecs-photos%", "image/jpeg"),
                null,
            )
        nbEnreg = cur?.use { it.count } ?: 0
      }
      if (nbEnreg >= 400) return
      dernierEnreg = now
      dernierCle = cle
      val nom = "p" + System.currentTimeMillis()
      val cvImg = ContentValues()
      cvImg.put(MediaStore.MediaColumns.DISPLAY_NAME, "$nom.jpg")
      cvImg.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
      cvImg.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/echecs-photos")
      val uriImg = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cvImg) ?: return
      res.openOutputStream(uriImg)?.use { img.compress(Bitmap.CompressFormat.JPEG, 88, it) }
      val cvTxt = ContentValues()
      cvTxt.put(MediaStore.MediaColumns.DISPLAY_NAME, "$nom.txt")
      cvTxt.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
      cvTxt.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/echecs-photos")
      val uriTxt = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cvTxt) ?: return
      val pts = coins.joinToString(" ") { "" + Math.round(it[0]) + "," + Math.round(it[1]) }
      val texte = fen + "\n" + moi + "\n" + pts + "\n"
      res.openOutputStream(uriTxt)?.use { it.write(texte.toByteArray()) }
      nbEnreg++
    } catch (e: Exception) {
      Log.w("Jeu", "enregistrement impossible : " + e.message)
    }
  }
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
      val vue: Lecteur.Vue,
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
    noter("PAROLE : $ecran")
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
    noter("statut : $t")
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
      val legaux = m.coupsLegaux(position())
      var uci = texte.trim().lowercase()
      if (uci !in legaux && uci.length == 4) {
        // départ et arrivée inversés (ex. a5g5 au lieu de g5a5) : on essaie dans l'autre sens
        val inverse = uci.substring(2, 4) + uci.substring(0, 2)
        if (inverse in legaux) uci = inverse
      }
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
  /** Donne mon coup. Renvoie false (sans rien annoncer) si ce coup est impossible d'après ce que la caméra voit. */
  private fun proposer(prefixe: String, prefixeDit: String = "", vue: Lecteur.Vue? = null): Boolean {
    val m = moteur ?: return true
    val pos = position()
    val legaux = m.coupsLegaux(pos)
    if (legaux.isEmpty()) {
      val echec = m.etat(pos)[1] == "1"
      parle(prefixe + if (echec) "Échec et mat. La partie est finie." else "Pat. Match nul.")
      statut("Partie terminée.")
      partieActive = false
      autoReprise = false
      return true
    }
    val coup = m.meilleurCoup(pos, TEMPS_MOTEUR) ?: return true
    val b = Partie.depuisFen(m.etat(pos)[0])
    if (vue != null && coup.length >= 4) {
      // ma pièce ne peut pas aller sur une case où la caméra voit déjà une de mes pièces : la position que j'ai en tête est fausse
      val de = (8 - (coup[1] - '0')) * 8 + (coup[0] - 'a')
      val vers = (8 - (coup[3] - '0')) * 8 + (coup[2] - 'a')
      val couleur = if (Character.isUpperCase(b[de])) 1 else 2
      if (vue.occupant(vers) == couleur) return false
    }
    val phrase = Partie.decrire(b, coup)
    val dite = Partie.dire(b, coup)
    occAvant = Partie.occupation(b)
    noter("PROPOSE $coup (coups avant : ${coups.size}, derniers : ${coups.takeLast(4)})")
    dernierePropA = SystemClock.elapsedRealtime()
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
    return true
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
      val plateauImg = trouverPlateau(img, det)
      if (plateauImg == null) {
        _etat.update { it.copy(image = Detecteur.dessine(img, null, null)) }
        statut("Je cherche l'échiquier…")
        return
      }
      // la caméra vient de bouger et je ne retrouve pas le quadrillage : l'ancien est sans doute faux, j'attends
      if (!plateauVu && SystemClock.elapsedRealtime() - dernierMouvement < 400L) {
        statut("Je cherche l'échiquier…")
        verifDemandeeA = SystemClock.elapsedRealtime() + 800
        return
      }
      for (rot in ordre) {
        // l'image reste droite (les pièces doivent être debout pour être reconnues) : c'est le plateau qu'on
        // tourne, en décalant les coins d'un cran (4 orientations possibles du plateau)
        val im = img
        val rc1 = coinsPour(plateauImg, img, rot)
        var px1 = Array(4) { doubleArrayOf(rc1.coins[it][0] * im.width, rc1.coins[it][1] * im.height) }
        if (moi == 'b') px1 = arrayOf(px1[2], px1[3], px1[0], px1[1])
        val r0 = det.detecte(im, px1) ?: continue
        // recalage fin des coins grâce aux pièces (la position attendue est connue)
        px1 = Detecteur.raffine(r0.pts, px1, candidats)
        val r1 = Detecteur.Resultat(Detecteur.observer(r0.pts, px1) ?: r0.obs, r0.nbPieces, r0.pts)
        val vue1 = Detecteur.vue(r0.pts, px1)
        val act1 = vue1.ecart(b)
        val notes1 = Lecteur.classeSouple(vue1, b, legaux)
        val q = minOf(act1, notes1[0].ecart)
        if (meil == null || q < meil.qualite) meil = Lecture(rot, im, px1, rc1, r1, act1, notes1, q, r0.obs, vue1)
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
      val margeF = Partie.margeFine(notes)
      // pièces vues en trop (pièces prises posées près de l'échiquier, doublons) : elles gonflent tous les écarts
      // de la même façon, donc on les met de côté pour juger si une lecture est crédible
      val attendues = b.count { it != '.' }
      val extras = maxOf(0, choix.vue.nbPieces() - attendues)
      noter(
          "lecture : écart=$actuel meilleur=${meilleur.coup}(${meilleur.ecart}) marge=$marge fine=${Math.round(margeF * 10) / 10.0}" +
              " extras=$extras vues=${choix.vue.nbPieces()} attendues=$attendues attendMonCoup=$attendMonCoup coups=${coups.size} moi=$moi")
      if (forceRelire) {
        relectures++
        if (relectures > 14) {
          forceRelire = false
          relectures = 0
        } else {
          statut("Je relis l'échiquier pour me recaler…")
          verifDemandeeA = SystemClock.elapsedRealtime() + 800
          if (relireTout(choix, relectures >= 6)) {
            forceRelire = false
            relectures = 0
          }
          return
        }
      }
      if (actuel - extras <= 1) enregistrer(imgOk, e[0], px, coups.size)
      _etat.update {
        it.copy(
            image = Detecteur.dessine(imgOk, px, r.obs),
            details =
                "pièces vues ${r.nbPieces} · écart actuel $actuel · meilleur ${meilleur.coup} (${meilleur.ecart})" +
                    " · marge $marge (fine %.1f) · plateau %.2f · rotation ${choix.rot}".format(margeF, rc.score),
        )
      }

      // marge d'un seul cran : acceptée si l'écart fin est net ; le coup est alors confirmé par une 2e photo
      // juste après que je t'ai donné ton coup, l'adversaire n'a pas eu le temps de répondre : on est plus exigeant
      // (un coup « fantôme » dû à une main ou une ombre ne doit pas me faire annoncer un 2e coup de suite)
      val recent = SystemClock.elapsedRealtime() - dernierePropA < 6000L
      // le coup doit coller mieux que « personne n'a joué » : en nombre d'erreurs, ou (à égalité) en précision
      val cActuel = choix.vue.compare(b)
      val actuelFin = cActuel[0] + cActuel[1]
      val mieuxQueRien =
          actuel - meilleur.ecart >= (if (recent) 2 else 1) ||
              actuelFin - meilleur.fin >= (if (recent) 1.6 else 0.7)
      val plausible =
          meilleur.ecart - extras <= 4 && (marge >= 2 || (marge >= 1 && margeF >= 0.25)) && mieuxQueRien
      if (attendMonCoup) {
        val av = occAvant
        // il a joué son coup : la photo ressemble plus à la position actuelle qu'à celle d'avant,
        // ou bien on reconnaît déjà le coup de l'adversaire (donc il a forcément joué le sien)
        // (une lecture "plausible" ne suffit pas : sinon on pourrait annoncer un 2e coup avant que tu aies joué)
        if (av != null && actuel < Partie.ecart(r.obs, av)) attendMonCoup = false
      }
      if (!plausible) {
        coupEnAttente = null
        if (attendMonCoup) {
          // On attend que le joueur joue son coup : on ne se plaint pas pendant qu'il joue.
          val av = occAvant
          val eAv = if (av != null) Partie.ecart(r.obs, av) else 99
          if (eAv - extras > 3 && actuel - extras > 3) {
            attenteVaine++
            if (attenteVaine >= 3) {
              // la photo ne ressemble ni à avant ni à maintenant : les deux ont dû jouer, on relit normalement
              attendMonCoup = false
              attenteVaine = 0
            }
          } else {
            attenteVaine = 0
          }
          if (attendMonCoup) {
            statut("J'attends que tu joues ton coup.")
            verifDemandeeA = SystemClock.elapsedRealtime() + 1200
            return
          }
        }
        if (actuel - extras >= 2) petitEcart++ else petitEcart = 0
        if (actuel - extras >= 4) {
          incertain++
          if (incertain == 3 || incertain == 8) doutePlateau = true // le quadrillage est peut-être décalé
          if (incertain >= 2 && resynchroniser(choix.vue, meilleur.ecart, extras)) {
            incertain = 0
            return
          }
          if (incertain >= 4 && relireTout(choix, incertain >= 12)) {
            incertain = 0
            return
          }
          statut("Je ne reconnais pas le coup (essai $incertain).")
          if (incertain == 4) parle("Je ne comprends pas le coup de l'adversaire. Vérifie que ses pièces sont bien dans leur case.")
          if (incertain == 10) parle("Tape le coup de l'adversaire dans la case en bas de l'écran.")
          verifDemandeeA = SystemClock.elapsedRealtime() + 800
        } else if (petitEcart >= 6 && resynchroniser(choix.vue, meilleur.ecart, extras)) {
          // la photo ne change plus mais ne colle pas tout à fait avec la partie : je cherche ce qui s'est passé
          petitEcart = 0
          incertain = 0
        } else {
          incertain = 0
          statut("Pas de nouveau coup. J'attends l'adversaire.")
        }
        return
      }
      attendMonCoup = false
      incertain = 0
      petitEcart = 0
      coinsSauves = rc.coins
      rotMem = choix.rot
      val fort = (marge >= 3 && actuel - meilleur.ecart >= 4) || (meilleur.ecart - extras <= 1 && marge >= 2 && actuel - meilleur.ecart >= 2)
      if ((!fort || recent) && coupEnAttente != meilleur.coup) {
        coupEnAttente = meilleur.coup
        statut("Coup probable : ${Partie.decrire(b, meilleur.coup)}. Je vérifie…")
        verifDemandeeA = SystemClock.elapsedRealtime() + 800
        return
      }
      coupEnAttente = null
      val phrase = Partie.decrire(b, meilleur.coup)
      noter("ADVERSAIRE accepté : ${meilleur.coup} (écart=$actuel, marge=$marge, fort=$fort, récent=$recent)")
      coups.add(meilleur.coup)
      sauver()
      enregistrer(imgOk, m.etat(position())[0], px, coups.size)
      if (!proposer("Adversaire : $phrase. ", "", choix.vue)) {
        // le coup que je voulais te donner est impossible sur l'échiquier réel : je me suis trompé plus tôt
        coups.removeAt(coups.size - 1)
        sauver()
        majPartie()
        forceRelire = true
        relectures = 0
        parle("Je ne suis plus sûr de la position. Je relis l'échiquier. Ne touche à rien.")
        statut("Je relis l'échiquier pour me recaler…")
        verifDemandeeA = SystemClock.elapsedRealtime() + 800
      }
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

  // dernière position sûre du plateau (fractions de l'image non tournée) : sert quand une image est mauvaise
  private var plateauSur: Array<DoubleArray>? = null
  private var plateauSurA = 0L
  private var doutePlateau = false
  private var plateauVu = true // false quand le quadrillage n'a pas été retrouvé sur la dernière image

  private fun dansQuad(q: Array<DoubleArray>, x: Double, y: Double): Boolean {
    var pos = 0
    var neg = 0
    for (i in 0..3) {
      val a = q[i]
      val b = q[(i + 1) % 4]
      val c = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0])
      if (c >= 0) pos++ else neg++
    }
    return pos == 4 || neg == 4
  }

  private fun fractionDedans(q: Array<DoubleArray>, pieds: List<DoubleArray>): Double {
    if (pieds.isEmpty()) return 1.0
    var n = 0
    for (p in pieds) if (dansQuad(q, p[0], p[1] - 3.0)) n++
    return n.toDouble() / pieds.size
  }

  /**
   * Repère l'échiquier par son quadrillage (aidé par les pieds des pièces). Si l'image est mauvaise
   * (main devant, pièces qui cachent), on garde la dernière position sûre : le téléphone ne bouge pas.
   */
  private fun trouverPlateau(img: Bitmap, det: Detecteur): Array<DoubleArray>? {
    val maintenant = SystemClock.elapsedRealtime()
    val sur = plateauSur
    // si la lecture est mauvaise depuis un moment, on refait la recherche à neuf (sans a priori ni pieds des pièces)
    val doute = doutePlateau
    doutePlateau = false
    // sur trépied : on ne refait pas toute la recherche à chaque image
    // (pas de raccourci si ça bouge : le plateau a pu changer de place dans l'image)
    if (!doute && sur != null && telephone && maintenant - plateauSurA < 6000L && maintenant - dernierMouvement > 1500L) {
      plateauVu = true
      return sur
    }
    try {
      val g = Detecteur.gris1000(img)
      val toute = arrayOf(
          doubleArrayOf(0.0, 0.0),
          doubleArrayOf(img.width.toDouble(), 0.0),
          doubleArrayOf(img.width.toDouble(), img.height.toDouble()),
          doubleArrayOf(0.0, img.height.toDouble()),
      )
      val pts = det.detecte(img, toute)?.pts ?: emptyList()
      val s = g.w.toDouble() / img.width
      val pieds = ArrayList<DoubleArray>()
      for (p in pts) if (p.conf > 0.4) pieds.add(doubleArrayOf(p.x * s, p.y * s))
      // le plateau ne saute pas d'une case entre deux photos : on favorise la position trouvée juste avant
      val avant: Array<DoubleArray>? =
          if (doute || sur == null) null else Array(4) { doubleArrayOf(sur[it][0] * g.w, sur[it][1] * g.h) }
      val res = Lattice.trouve(g, if (doute) ArrayList<DoubleArray>() else pieds, avant)
      // au départ d'une partie, toutes les pièces sont sur le plateau : un quadrillage qui n'en contient que la moitié est faux
      if (res != null && res.v >= Lattice.SEUIL &&
          (partieActive || doute || pieds.size < 12 || fractionDedans(res.px, pieds) >= 0.6)) {
        val fr = Array(4) { doubleArrayOf(res.px[it][0] / g.w, res.px[it][1] / g.h) }
        plateauSur = fr
        plateauSurA = maintenant
        plateauVu = true
        return fr
      }
    } catch (e: Exception) {
      Log.w(TAG, "repérage du plateau : $e")
    }
    plateauVu = false
    return sur
  }

  /**
   * Les 4 coins du plateau (fractions de l'image), décalés de rot/90 crans : on ne sait pas de quel côté
   * sont les blancs. Ordre de base : haut-gauche, haut-droit, bas-droit, bas-gauche de l'image.
   */
  private fun coinsPour(fr: Array<DoubleArray>, img: Bitmap, rot: Int): Coins.Resultat {
    val px = Array(4) { doubleArrayOf(fr[it][0] * img.width, fr[it][1] * img.height) }
    val base = Lattice.pourRotation(px, img.width, img.height, 0)
    val dec = (rot / 90) % 4
    return Coins.Resultat(Array(4) { base[(it + dec) % 4] }, 1.0)
  }

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
    val plateauImg = trouverPlateau(img, det)
    if (plateauImg == null || (!plateauVu && SystemClock.elapsedRealtime() - dernierMouvement < 400L)) {
      statut("Je cherche l'échiquier…")
      return
    }
    for (rot in ordre) {
      val im = img
      val rc1 = coinsPour(plateauImg, img, rot)
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
  private fun relireTout(choix: Lecture, sansGarde: Boolean = false): Boolean {
    // les coins de la lecture sont déjà retournés quand je joue les noirs : le plateau est dans le bon sens
    val lu = Detecteur.lire(choix.r.pts, choix.px)
    // Des pièces cachées derrière d'autres peuvent manquer à la lecture : on ne remplace pas une partie suivie
    // par une lecture à qui il manque des pièces (sauf si on est perdu depuis longtemps).
    val m = moteur
    if (!sansGarde && m != null && (coups.isNotEmpty() || baseFen != null)) {
      try {
        val suivi = m.etat(position())[0].substringBefore(' ').count { it.isLetter() }
        val vues = lu.count { it != '.' }
        if (vues < suivi - 1) {
          statut("Je ne vois pas toutes les pièces (certaines sont cachées). Je réessaie.")
          return false
        }
      } catch (e: Exception) {
      }
    }
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
  private fun resynchroniser(vue: Lecteur.Vue, ecartNormal: Int, extras: Int = 0): Boolean {
    val m = moteur ?: return false
    if (coups.isEmpty()) return false
    val dernier = coups.last()
    val avant = ArrayList<String>(coups.subList(0, coups.size - 1))
    val bAvant = Partie.depuisFen(m.etat(positionDe(avant))[0])
    class Cand(val ecart: Int, val seq: List<String>, val occ: IntArray)
    val cands = ArrayList<Cand>()
    fun note(b: CharArray, seq: List<String>) {
      cands.add(Cand(vue.ecart(b), seq, Partie.occupation(b)))
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
    if (best.ecart - extras > 2 || marge < 2 || best.ecart >= ecartNormal) return false

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
    val periodique = maintenant - derniereCapture > (if (telephone) 600L else VERIF_MS)
    // Téléphone tenu à la main : l'image ne se calme jamais, on regarde quand même de temps en temps.
    val force = telephone && !calme && maintenant - derniereCapture > 500L
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
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build())
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

  @Volatile private var imageDirect: Bitmap? = null
  @Volatile private var imageDirectA = 0L

  private fun analyserTel(img: ImageProxy) {
    try {
      val rot = img.imageInfo.rotationDegrees
      val maintenant = SystemClock.elapsedRealtime()
      // pendant un mouvement on garde l'image vidéo (couleur) : pas besoin d'attendre une photo
      if (maintenant - dernierMouvement < 600L && !occupe.get()) {
        var b = img.toBitmap()
        if (rot != 0) {
          val m = Matrix().apply { postRotate(rot.toFloat()) }
          b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        imageDirect = b
        imageDirectA = maintenant
      }
      val plan = img.planes[0]
      val w0 = img.width
      val h0 = img.height
      // luminosité seulement, sur une petite grille (suffit pour mesurer le mouvement)
      val w = 160
      val h = 120
      val buf = plan.buffer
      val stride = plan.rowStride
      val pix = plan.pixelStride
      val y = ByteArray(w * h)
      for (r in 0 until h) for (c in 0 until w) {
        val o = (r * h0 / h) * stride + (c * w0 / w) * pix
        y[r * w + c] = ((buf.get(o).toInt() and 255) * 3 / 10 + (buf.get(o + 1).toInt() and 255) * 6 / 10 + (buf.get(o + 2).toInt() and 255) / 10).toByte()
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
        if (telephone) {
          val d = imageDirect
          val now = SystemClock.elapsedRealtime()
          if (d != null && now - imageDirectA < 300L && now - dernierMouvement < 600L) {
            img = d
            imageDirect = null
          } else img = photoTelephone()
        }
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
