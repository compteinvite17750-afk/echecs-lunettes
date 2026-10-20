package fr.echecs.lunettes

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Détection des pièces (modèle YOLO entraîné sur Colab) et outils d'image. */
class Detecteur(modele: ByteArray) {

  private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
  private val session: OrtSession = env.createSession(modele, OrtSession.SessionOptions())
  private val nomEntree: String = session.inputNames.first()

  /** Pied d'une pièce détectée : position dans l'image (pixels) et luminosité. */
  class Pt(val x: Double, val y: Double, val lum: Double, val cls: Int = 0, val conf: Double = 0.0)

  class Resultat(val obs: IntArray, val nbPieces: Int, val pts: List<Pt> = emptyList())

  private class Boite(val cx: Float, val cy: Float, val w: Float, val h: Float, val conf: Float, val cls: Int = 0) {
    fun iou(o: Boite): Float {
      val x1 = max(cx - w / 2, o.cx - o.w / 2)
      val y1 = max(cy - h / 2, o.cy - o.h / 2)
      val x2 = min(cx + w / 2, o.cx + o.w / 2)
      val y2 = min(cy + h / 2, o.cy + o.h / 2)
      val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
      return inter / (w * h + o.w * o.h - inter + 1e-9f)
    }
  }

  /**
   * coinsPx : haut-gauche, haut-droit, bas-droit, bas-gauche en pixels, dans le repère "blancs en bas"
   * (déjà retournés si on joue les noirs). Retourne l'occupation des 64 cases.
   */
  fun detecte(img: Bitmap, coinsPx: Array<DoubleArray>): Resultat? {
    Coins.homographie(coinsPx, Coins.SQ) ?: return null

    // On ne garde que la zone autour de l'échiquier (+ de la marge en haut pour les pièces hautes)
    val xs = coinsPx.map { it[0] }
    val ys = coinsPx.map { it[1] }
    val bw = xs.max() - xs.min()
    val bh = ys.max() - ys.min()
    val cx0 = max(0, (xs.min() - 0.08 * bw).toInt())
    val cx1 = min(img.width, (xs.max() + 0.08 * bw).toInt())
    val cy0 = max(0, (ys.min() - 0.35 * bh).toInt())
    val cy1 = min(img.height, (ys.max() + 0.05 * bh).toInt())
    val cw = cx1 - cx0
    val ch = cy1 - cy0
    if (cw < 32 || ch < 32) return null
    val zone = Bitmap.createBitmap(img, cx0, cy0, cw, ch)

    // Image 640x640 (comme à l'entraînement), fond gris
    val s = 640.0 / max(cw, ch)
    val rw = max(1, (cw * s).toInt())
    val rh = max(1, (ch * s).toInt())
    val petit = Bitmap.createScaledBitmap(zone, rw, rh, true)
    val px = IntArray(rw * rh)
    petit.getPixels(px, 0, rw, 0, 0, rw, rh)
    val plan = 640 * 640
    val data = FloatArray(3 * plan) { 114f / 255f }
    for (y in 0 until rh) {
      for (x in 0 until rw) {
        val c = px[y * rw + x]
        val i = y * 640 + x
        data[i] = ((c shr 16) and 255) / 255f
        data[plan + i] = ((c shr 8) and 255) / 255f
        data[2 * plan + i] = (c and 255) / 255f
      }
    }
    val sortie: Array<FloatArray> =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 640, 640)).use { t ->
          session.run(mapOf(nomEntree to t)).use { r ->
            @Suppress("UNCHECKED_CAST")
            (r[0].value as Array<Array<FloatArray>>)[0]
          }
        }

    val nc = sortie.size - 4
    val n = sortie[0].size
    val boites = ArrayList<Boite>()
    for (i in 0 until n) {
      var m = 0f
      var mc = 0
      for (c in 0 until nc) {
        if (sortie[4 + c][i] > m) {
          m = sortie[4 + c][i]
          mc = c
        }
      }
      if (m > 0.25f) boites.add(Boite(sortie[0][i], sortie[1][i], sortie[2][i], sortie[3][i], m, mc))
    }
    boites.sortByDescending { it.conf }
    val finales = ArrayList<Boite>()
    for (b in boites) if (finales.all { it.iou(b) < 0.5f }) finales.add(b)

    // Luminosité de chaque pièce (pour savoir si elle est blanche ou noire)
    val zpx = IntArray(cw * ch)
    zone.getPixels(zpx, 0, cw, 0, 0, cw, ch)
    val pts = ArrayList<Pt>()
    for (b in finales) {
      val cx = b.cx / s
      val cy = b.cy / s
      val w = b.w / s
      val h = b.h / s
      val y1 = max(0, (cy - h * 0.2).toInt())
      val y2 = min(ch, max(y1 + 1, (cy + h * 0.2).toInt() + 1))
      val x1 = max(0, (cx - w * 0.2).toInt())
      val x2 = min(cw, max(x1 + 1, (cx + w * 0.2).toInt() + 1))
      var tot = 0.0
      var k = 0
      for (yy in y1 until y2) for (xx in x1 until x2) {
        tot += lum(zpx[yy * cw + xx])
        k++
      }
      if (k > 0) pts.add(Pt(cx + cx0, cy + cy0 + BASE * h, tot / k, b.cls, b.conf.toDouble()))
    }
    val obs = observer(pts, coinsPx) ?: IntArray(64)
    return Resultat(obs, finales.size, pts)
  }

  companion object {
    /** Occupation des 64 cases (0 vide, 1 blanc, 2 noir) à partir des pieds de pièces et des 4 coins. */
    fun observer(pts: List<Pt>, coinsPx: Array<DoubleArray>): IntArray? {
      if (Coins.homographie(coinsPx, Coins.SQ) == null) return null
      return Lecteur.occupation(
          coinsPx,
          DoubleArray(pts.size) { pts[it].x },
          DoubleArray(pts.size) { pts[it].y },
          DoubleArray(pts.size) { pts[it].lum },
      )
    }

    /** Lecture "souple" : chaque pièce est rattachée à la case attendue la plus proche. */
    fun vue(pts: List<Pt>, coinsPx: Array<DoubleArray>): Lecteur.Vue =
        Lecteur.vue(
            coinsPx,
            DoubleArray(pts.size) { pts[it].x },
            DoubleArray(pts.size) { pts[it].y },
            DoubleArray(pts.size) { pts[it].lum },
            IntArray(pts.size) { pts[it].cls },
            DoubleArray(pts.size) { pts[it].conf },
        )

    /** Position complète lue (lettres FEN, majuscule = blanc), vue depuis la caméra. */
    fun lire(pts: List<Pt>, coinsPx: Array<DoubleArray>): CharArray =
        Lecteur.lire(
            coinsPx,
            DoubleArray(pts.size) { pts[it].x },
            DoubleArray(pts.size) { pts[it].y },
            DoubleArray(pts.size) { pts[it].lum },
            IntArray(pts.size) { pts[it].cls },
            DoubleArray(pts.size) { pts[it].conf },
        )

    /**
     * Recale les 4 coins pour que les pièces vues collent au mieux avec la position attendue
     * (la position actuelle ou celle après l'un des coups légaux).
     */
    fun raffine(pts: List<Pt>, coins: Array<DoubleArray>, candidats: List<IntArray>): Array<DoubleArray> {
      fun cout(c: Array<DoubleArray>): Int {
        val o = observer(pts, c) ?: return 999
        var m = 999
        for (a in candidats) {
          var n = 0
          for (i in 0 until 64) if (o[i] != a[i]) n++
          if (n < m) m = n
        }
        return m
      }
      var best = Array(4) { coins[it].clone() }
      var bc = cout(best)
      val bw = coins.maxOf { it[0] } - coins.minOf { it[0] }
      val bh = coins.maxOf { it[1] } - coins.minOf { it[1] }
      // le quadrillage est déjà précis : on ne laisse pas le recalage s'éloigner de plus de ~0,4 case
      val maxDx = 0.05 * bw
      val maxDy = 0.05 * bh
      for (pas in doubleArrayOf(0.04, 0.02, 0.01, 0.005)) {
        var mieux = true
        var tours = 0
        while (mieux && tours < 6 && bc > 0) {
          mieux = false
          tours++
          for (k in 0..3) for (axe in 0..1) for (sg in intArrayOf(-1, 1)) {
            val t = Array(4) { best[it].clone() }
            t[k][axe] += sg * pas * (if (axe == 0) bw else bh)
            if (Math.abs(t[k][axe] - coins[k][axe]) > (if (axe == 0) maxDx else maxDy)) continue
            val c = cout(t)
            if (c < bc) {
              best = t
              bc = c
              mieux = true
            }
          }
        }
      }
      return best
    }

    /** Où se trouve le "pied" de la pièce dans sa boîte (0 = centre, 0.5 = bas). */
    const val BASE = 0.40

    fun lum(c: Int): Double =
        ((c shr 16) and 255) * 0.299 + ((c shr 8) and 255) * 0.587 + (c and 255) * 0.114

    /** Image en gris réduite à 1000 pixels de large au plus, pour le repérage du quadrillage. */
    fun gris1000(img: Bitmap): Coins.Gris {
      val w = min(1000, img.width)
      val h = max(1, (img.height * w.toDouble() / img.width).toInt())
      val b = Bitmap.createScaledBitmap(img, w, h, true)
      val px = IntArray(w * h)
      b.getPixels(px, 0, w, 0, 0, w, h)
      val g = FloatArray(w * h) { lum(px[it]).toFloat() }
      return Coins.Gris(w, h, g)
    }

    /** Image en gris réduite à 500 pixels de large, pour la recherche de l'échiquier. */
    fun gris500(img: Bitmap): Coins.Gris {
      val w = 500
      val h = max(1, (img.height * 500.0 / img.width).toInt())
      val b = Bitmap.createScaledBitmap(img, w, h, true)
      val px = IntArray(w * h)
      b.getPixels(px, 0, w, 0, 0, w, h)
      val g = FloatArray(w * h) { lum(px[it]).toFloat() }
      return Coins.Gris(w, h, g)
    }

    /** Décode un JPEG sans toucher à l'orientation (la rotation est gérée par l'appelant). */
    fun decodeBrut(bytes: ByteArray): Bitmap? {
      val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
      var sample = 1
      while (max(opts.outWidth, opts.outHeight) / sample > 3000) sample *= 2
      return BitmapFactory.decodeByteArray(
          bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Décode une photo (JPEG/HEIC) en tenant compte de l'orientation EXIF, réduite si trop grande. */
    fun decode(bytes: ByteArray): Bitmap? {
      val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
      var sample = 1
      while (max(opts.outWidth, opts.outHeight) / sample > 3000) sample *= 2
      val bmp =
          BitmapFactory.decodeByteArray(
              bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
      val orient =
          try {
            ByteArrayInputStream(bytes).use {
              ExifInterface(it)
                  .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
          } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
          }
      val m = Matrix()
      when (orient) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
          m.postRotate(90f)
          m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
          m.postRotate(270f)
          m.postScale(-1f, 1f)
        }
      }
      if (m.isIdentity) return bmp
      return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Copie réduite de l'image avec le quadrillage dessiné (pour vérifier à l'écran). */
    fun dessine(img: Bitmap, coinsPx: Array<DoubleArray>?, obs: IntArray?): Bitmap {
      val f = min(1.0, 1000.0 / max(img.width, img.height))
      val out =
          Bitmap.createScaledBitmap(
                  img, max(1, (img.width * f).toInt()), max(1, (img.height * f).toInt()), true)
              .copy(Bitmap.Config.ARGB_8888, true)
      if (coinsPx == null) return out
      val pts = Array(4) { doubleArrayOf(coinsPx[it][0] * f, coinsPx[it][1] * f) }
      val h = Coins.homographie(Coins.SQ, pts) ?: return out
      val c = Canvas(out)
      val rouge = Paint().apply { color = Color.RED; strokeWidth = 3f }
      val vert = Paint().apply { color = Color.GREEN; strokeWidth = 3f }
      for (i in 0..8) {
        val a = Coins.applique(h, i.toDouble(), 0.0)
        val b = Coins.applique(h, i.toDouble(), 8.0)
        c.drawLine(a[0].toFloat(), a[1].toFloat(), b[0].toFloat(), b[1].toFloat(), rouge)
        val d = Coins.applique(h, 0.0, i.toDouble())
        val e = Coins.applique(h, 8.0, i.toDouble())
        c.drawLine(d[0].toFloat(), d[1].toFloat(), e[0].toFloat(), e[1].toFloat(), vert)
      }
      if (obs != null) {
        val blanc = Paint().apply { color = Color.WHITE }
        val noir = Paint().apply { color = Color.BLACK }
        val bord = Paint().apply { color = Color.MAGENTA; style = Paint.Style.STROKE; strokeWidth = 2f }
        for (i in 0 until 64) {
          if (obs[i] == 0) continue
          val p = Coins.applique(h, i % 8 + 0.5, i / 8 + 0.5)
          c.drawCircle(p[0].toFloat(), p[1].toFloat(), 7f, if (obs[i] == 1) blanc else noir)
          c.drawCircle(p[0].toFloat(), p[1].toFloat(), 7f, bord)
        }
      }
      return out
    }

    /** Image en gris à partir du plan Y d'une image vidéo (secours si la photo échoue). */
    fun depuisY(y: ByteArray, w: Int, h: Int): Bitmap {
      val px = IntArray(w * h) {
        val g = y[it].toInt() and 255
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
      }
      return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
  }
}
