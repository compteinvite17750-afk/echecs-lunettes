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
  private var rotMem = 0

  private class Lecture(
      val rot: Int,
      val im: Bitmap,
      val px: Array<DoubleArray>,
      val rc: Coins.Resultat,
      val r: Detecteur.Resultat,
      val actuel: Int,
      val notes: List<Partie.Note>,
      val qualite: Int,
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
        if (meil == null || q < meil.qualite) meil = Lecture(rot, im, px1, rc1, r1, act1, notes1, q)
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
      rotMem = choix.rot
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
    // Téléphone tenu à la main : l'image bouge toujours, donc on regarde l'échiquier
    // toutes les 4 secondes sans attendre le calme.
    if (telephone && !occupe.get() && maintenant - derniereCapture > 4000 && partieEnCours()) {
      aBouge = false
      verifDemandeeA = 0
      capturer()
      return
    }
    if (!calme || occupe.get()) return
    val verif = verifDemandeeA in 1..maintenant
    val periodique = maintenant - derniereCapture > VERIF_MS
    if ((aBouge || verif || periodique) && partieEnCours()) {
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
                ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
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
