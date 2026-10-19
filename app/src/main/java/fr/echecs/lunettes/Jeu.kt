package fr.echecs.lunettes

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
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
    private const val CALME_MS = 1200L // temps sans mouvement avant de prendre la photo
    private const val VERIF_MS = 20000L // nouvelle vérification de temps en temps, même sans mouvement
    private const val TEMPS_MOTEUR = 1500
  }

  private val _etat = MutableStateFlow(EcranEtat())
  val etat: StateFlow<EcranEtat> = _etat.asStateFlow()

  private val prefs = app.getSharedPreferences("partie", Context.MODE_PRIVATE)
  private var coups = ArrayList<String>()
  private var moi = 'w'
  private var coinsSauves: Array<DoubleArray>? = null

  private var moteur: Moteur? = null
  private var detecteur: Detecteur? = null
  private var tts: TextToSpeech? = null

  // lunettes
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var flux: Stream? = null
  private var sdkPret = false
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

  init {
    charger()
    tts =
        TextToSpeech(app) { st ->
          if (st == TextToSpeech.SUCCESS) tts?.language = Locale.FRANCE
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
        .putString("coins", coinsSauves?.joinToString(",") { "${it[0]},${it[1]}" })
        .apply()
  }

  private fun position(): String =
      if (coups.isEmpty()) "position startpos" else "position startpos moves " + coups.joinToString(" ")

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
  private fun parle(t: String) {
    _etat.update { it.copy(dernierePhrase = t) }
    tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "phrase")
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

  fun repeter() {
    val p = _etat.value.dernierePhrase
    if (p.isNotBlank()) parle(p)
  }

  /** Demande à Stockfish mon coup, l'annonce et l'ajoute à la partie. */
  private fun proposer(prefixe: String) {
    val m = moteur ?: return
    val pos = position()
    val legaux = m.coupsLegaux(pos)
    if (legaux.isEmpty()) {
      val echec = m.etat(pos)[1] == "1"
      parle(prefixe + if (echec) "Échec et mat. La partie est finie." else "Pat. Match nul.")
      statut("Partie terminée.")
      return
    }
    val coup = m.meilleurCoup(pos, TEMPS_MOTEUR) ?: return
    val b = Partie.depuisFen(m.etat(pos)[0])
    val phrase = Partie.decrire(b, coup)
    coups.add(coup)
    sauver()
    val pos2 = position()
    val fini = m.coupsLegaux(pos2).isEmpty()
    val echec = m.etat(pos2)[1] == "1"
    val suite =
        when {
          fini && echec -> " Échec et mat, tu as gagné !"
          fini -> " Pat, match nul."
          echec -> " Échec."
          else -> ""
        }
    parle("${prefixe}Joue : $phrase.$suite")
    statut("TON COUP : $phrase.$suite")
    majPartie()
  }

  private fun partieEnCours(): Boolean {
    val m = moteur ?: return false
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

      val rc = Coins.trouve(Detecteur.gris500(img), coinsSauves)
      if (rc.score < Coins.SEUIL_OK) {
        _etat.update { it.copy(image = Detecteur.dessine(img, null, null)) }
        statut("Je ne vois pas bien l'échiquier (score %.2f).".format(rc.score))
        return
      }
      var px = Array(4) { doubleArrayOf(rc.coins[it][0] * img.width, rc.coins[it][1] * img.height) }
      if (moi == 'b') px = arrayOf(px[2], px[3], px[0], px[1])
      val r = det.detecte(img, px) ?: return statut("Analyse impossible.")
      val b = Partie.depuisFen(e[0])
      val actuel = Partie.ecart(r.obs, Partie.occupation(b))
      val notes = Partie.classe(b, legaux, r.obs)
      val meilleur = notes[0]
      val marge = Partie.marge(notes)
      _etat.update {
        it.copy(
            image = Detecteur.dessine(img, px, r.obs),
            details =
                "pièces vues ${r.nbPieces} · écart actuel $actuel · meilleur ${meilleur.coup} (${meilleur.ecart})" +
                    " · marge $marge · plateau %.2f".format(rc.score),
        )
      }

      val plausible = meilleur.ecart <= actuel - 2 && marge >= 1 && meilleur.ecart <= 14
      if (!plausible) {
        coupEnAttente = null
        if (actuel >= 4) {
          incertain++
          statut("Je ne reconnais pas le coup (essai $incertain).")
          if (incertain == 3) parle("Je n'arrive pas à lire le coup. Regarde bien l'échiquier.")
          verifDemandeeA = SystemClock.elapsedRealtime() + 1500
        } else {
          incertain = 0
          statut("Pas de nouveau coup. J'attends l'adversaire.")
        }
        return
      }
      incertain = 0
      coinsSauves = rc.coins
      val fort = marge >= 3 && actuel - meilleur.ecart >= 4
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
    if (!calme || occupe.get()) return
    val verif = verifDemandeeA in 1..maintenant
    val periodique = maintenant - derniereCapture > VERIF_MS
    if ((aBouge || verif || periodique) && partieEnCours()) {
      aBouge = false
      verifDemandeeA = 0
      capturer()
    }
  }

  /** Prend une photo nette avec les lunettes et l'analyse. */
  fun capturer() {
    if (!occupe.compareAndSet(false, true)) return
    viewModelScope.launch {
      try {
        var img: Bitmap? = null
        val st = flux
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
        if (img == null) {
          val y = dernierY
          if (y != null) img = Detecteur.depuisY(y, dernierYw, dernierYh)
        }
        if (img == null) {
          statut("Pas d'image des lunettes. Démarre la caméra.")
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
