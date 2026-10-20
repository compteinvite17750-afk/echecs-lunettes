package fr.echecs.lunettes

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {

  private val jeu: Jeu by viewModels()

  private val permissionsAndroid =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
          Wearables.initialize(this)
          jeu.sdkInitialise()
        }
      }

  // Autorisation de la caméra des lunettes (passe par l'application Meta AI)
  private var suite: CancellableContinuation<PermissionStatus>? = null
  private val verrou = Mutex()
  private val permissionLunettes =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val st = result.getOrDefault(PermissionStatus.Denied)
        suite?.resume(st)
        suite = null
      }

  suspend fun demandePermission(p: Permission): PermissionStatus =
      verrou.withLock {
        suspendCancellableCoroutine { c ->
          suite = c
          c.invokeOnCancellation { suite = null }
          permissionLunettes.launch(p)
        }
      }

  private var vueEnAttente: PreviewView? = null
  private val permissionCameraTel =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val v = vueEnAttente
        if (ok && v != null) jeu.demarrerTelephone(this, v)
        vueEnAttente = null
      }

  private fun lancerTelephone(vue: PreviewView) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED) {
      jeu.demarrerTelephone(this, vue)
    } else {
      vueEnAttente = vue
      permissionCameraTel.launch(Manifest.permission.CAMERA)
    }
  }

  private val choixPhoto =
      registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) jeu.analyserFichier(uri)
      }

  private val choixModele =
      registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) jeu.importerModele(uri)
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxWidth()) {
          Ecran(
              jeu = jeu,
              connecter = { Wearables.startRegistration(this) },
              camera = { jeu.demarrerCamera(::demandePermission) },
              telephone = ::lancerTelephone,
              photo = { choixPhoto.launch("image/*") },
              modele = { choixModele.launch("*/*") },
          )
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    val perms =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.INTERNET)
        else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.INTERNET)
    permissionsAndroid.launch(perms)
  }
}

@Composable
fun Ecran(
    jeu: Jeu,
    connecter: () -> Unit,
    camera: () -> Unit,
    telephone: (PreviewView) -> Unit,
    photo: () -> Unit,
    modele: () -> Unit,
) {
  val e by jeu.etat.collectAsStateWithLifecycle()
  var saisie by remember { mutableStateOf("") }
  val large = Modifier.fillMaxWidth()
  val ctx = LocalContext.current
  val vue = remember {
    PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
  }

  Column(
      modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Spacer(Modifier.height(24.dp))
    Text("Échecs Lunettes", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text(e.statut, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    if (e.dernierePhrase.isNotBlank()) Text("🔊 " + e.dernierePhrase, fontSize = 16.sp)
    if (e.enAnalyse) Text("Analyse en cours…")

    HorizontalDivider()
    Text("1. Lunettes", fontWeight = FontWeight.Bold)
    Text("Enregistrement : ${e.enregistrement.ifBlank { "—" }}")
    Text("Lunettes : ${e.lunettes}   Vidéo : ${e.flux.ifBlank { "—" }}")
    Text("Mouvement : %.1f".format(e.mouvement))
    if (!e.estEnregistre) Button(onClick = connecter, modifier = large) { Text("Connecter les lunettes (Meta AI)") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = camera) { Text("Démarrer la caméra") }
      OutlinedButton(onClick = { jeu.arreterCamera() }) { Text("Arrêter") }
    }

    HorizontalDivider()
    Text("1 bis. Caméra du téléphone (sans lunettes)", fontWeight = FontWeight.Bold)
    Text("Pose le téléphone debout sur un support, face à l'échiquier, côté blancs.", fontSize = 13.sp)
    AndroidView(factory = { vue }, modifier = large.height(260.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { telephone(vue) }) { Text("Démarrer la caméra du téléphone") }
      OutlinedButton(onClick = { jeu.arreterTelephone() }) { Text("Arrêter") }
    }

    HorizontalDivider()
    Text("2. Partie", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { jeu.nouvellePartie(true) }) { Text("Nouvelle (blancs)") }
      OutlinedButton(onClick = { jeu.nouvellePartie(false) }) { Text("Nouvelle (noirs)") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { jeu.capturer() }) { Text("Analyser maintenant") }
      OutlinedButton(onClick = { jeu.repeter() }) { Text("Répéter") }
    }
    OutlinedButton(onClick = { jeu.changerStyle() }, modifier = large) {
      Text("Changer la façon de dire les cases")
    }
    OutlinedButton(onClick = { jeu.annuler() }, modifier = large) {
      Text("Annuler le dernier coup adverse")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
          value = saisie,
          onValueChange = { saisie = it },
          label = { Text("Coup adverse (ex. e7e5)") },
          singleLine = true,
          modifier = Modifier.weight(1f),
      )
      Button(onClick = {
        jeu.coupManuel(saisie)
        saisie = ""
      }) { Text("OK") }
    }
    if (e.partie.isNotBlank()) Text(e.partie, fontFamily = FontFamily.Monospace, fontSize = 15.sp)

    HorizontalDivider()
    Text("3. Ce que voit l'application", fontWeight = FontWeight.Bold)
    if (e.details.isNotBlank()) Text(e.details, fontSize = 12.sp)
    e.image?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = large) }
    OutlinedButton(onClick = photo, modifier = large) { Text("Tester avec une photo de la galerie") }
    if (e.modeleManquant) Button(onClick = modele, modifier = large) { Text("Charger le modèle (best.onnx)") }
    Spacer(Modifier.height(40.dp))
  }
}
