package fr.echecs.lunettes;

/**
 * Suit le plateau d'une image à l'autre (flux optique de Lucas-Kanade + homographie robuste RANSAC).
 * Les coins sont en coordonnées normalisées (0..1) de l'image, droite (même repère que Lattice).
 * Sert à garder le quadrillage collé à l'échiquier quand la tête bouge, sans refaire la recherche à chaque image.
 */
public final class Suivi {
  private static final int CIBLE = 320; // largeur de travail
  private static final int NIV = 4; // niveaux de pyramide
  private static final int DEMI = 7; // fenêtre LK : (2*DEMI+1)^2
  private static final int N = 256; // historique d'images

  private int pw, ph;
  private float[] prev; // image précédente (niveau 0)
  private double[][] quadPx; // 4 coins en pixels de travail, null = pas de plateau suivi
  private boolean actif;
  private int echecs; // images de suite sans suivi valide
  private long id;
  private final double[][] deltas = new double[N][];
  private final double[][][] quads = new double[N][][];
  public int derniersInliers;
  public int derniersPoints;
  private long graine = 12345;

  public synchronized void reset() {
    prev = null;
    quadPx = null;
    actif = false;
    echecs = 0;
    for (int i = 0; i < N; i++) {
      deltas[i] = null;
      quads[i] = null;
    }
  }

  public synchronized long id() {
    return id;
  }

  /** Vrai si on suit un plateau et que le suivi est bon. */
  public synchronized boolean sain() {
    return actif && echecs <= 2;
  }

  public synchronized String diag() {
    return "actif=" + actif + " echecs=" + echecs + " quad=" + (quadPx != null) + " id=" + id;
  }

  public synchronized boolean actif() {
    return actif;
  }

  /** Coins actuels (normalisés) ou null. */
  public synchronized double[][] quad() {
    if (!actif || quadPx == null) return null;
    return norm(quadPx);
  }

  /** Coins au moment de l'image numéro idRef (normalisés) ou null si inconnus. */
  public synchronized double[][] quadA(long idRef) {
    if (!actif) return null;
    if (idRef == id) return norm(quadPx);
    if (id - idRef >= N || idRef > id) return null;
    double[][] q = quads[(int) (idRef % N)];
    if (q == null) return null;
    double[][] c = new double[4][2];
    for (int i = 0; i < 4; i++) {
      c[i][0] = q[i][0];
      c[i][1] = q[i][1];
    }
    return c;
  }

  /** Le repérage (Lattice) a trouvé ces coins sur l'image numéro idRef : on recale le suivi, en rattrapant le mouvement depuis. */
  public synchronized void ancre(long idRef, double[][] q) {
    if (prev == null) return;
    double[][] p = new double[4][2];
    for (int i = 0; i < 4; i++) {
      p[i][0] = q[i][0] * pw;
      p[i][1] = q[i][1] * ph;
    }
    if (idRef < id && id - idRef < N) {
      double[] m = identite();
      boolean ok = true;
      for (long k = idRef + 1; k <= id; k++) {
        double[] d = deltas[(int) (k % N)];
        if (d == null) {
          ok = false;
          break;
        }
        m = mul(d, m);
      }
      if (ok) {
        for (int i = 0; i < 4; i++) p[i] = appliquer(m, p[i][0], p[i][1]);
      }
    }
    quadPx = p;
    actif = true;
    echecs = 0;
    quads[(int) (id % N)] = norm(quadPx);
  }

  public synchronized void arreter() {
    actif = false;
    quadPx = null;
  }

  /** Nouvelle image (luminosité w x h). */
  public synchronized void frame(byte[] y, int w, int h) {
    int f = Math.max(1, Math.round((float) w / CIBLE));
    int nw = w / f, nh = h / f;
    float[] g = new float[nw * nh];
    if (f == 1) {
      for (int i = 0; i < g.length; i++) g[i] = y[i] & 255;
    } else {
      float inv = 1f / (f * f);
      for (int j = 0; j < nh; j++)
        for (int i = 0; i < nw; i++) {
          int s = 0;
          for (int dj = 0; dj < f; dj++) {
            int o = (j * f + dj) * w + i * f;
            for (int di = 0; di < f; di++) s += y[o + di] & 255;
          }
          g[j * nw + i] = s * inv;
        }
    }
    if (prev == null || nw != pw || nh != ph) {
      // première image, ou l'image a changé de forme (téléphone tourné) : on repart de zéro
      if (prev != null) {
        actif = false;
        quadPx = null;
      }
      pw = nw;
      ph = nh;
      prev = g;
      return;
    }
    id++;
    int slot = (int) (id % N);
    deltas[slot] = identite();
    if (!actif || quadPx == null) {
      quads[slot] = null;
      prev = g;
      return;
    }
    double[] hd = suivre(prev, g);
    if (hd != null) {
      double[][] nq = new double[4][];
      for (int i = 0; i < 4; i++) nq[i] = appliquer(hd, quadPx[i][0], quadPx[i][1]);
      if (valide(nq, quadPx)) {
        quadPx = nq;
        deltas[slot] = hd;
        echecs = 0;
      } else echecs++;
    } else echecs++;
    if (echecs > 8) {
      actif = false;
      quadPx = null;
    }
    quads[slot] = actif ? norm(quadPx) : null;
    prev = g;
  }

  // ---------------------------------------------------------------- géométrie
  private double[][] norm(double[][] p) {
    double[][] c = new double[4][2];
    for (int i = 0; i < 4; i++) {
      c[i][0] = p[i][0] / pw;
      c[i][1] = p[i][1] / ph;
    }
    return c;
  }

  private static double[] identite() {
    return new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
  }

  private static double[] mul(double[] a, double[] b) {
    double[] r = new double[9];
    for (int i = 0; i < 3; i++)
      for (int j = 0; j < 3; j++) {
        double s = 0;
        for (int k = 0; k < 3; k++) s += a[i * 3 + k] * b[k * 3 + j];
        r[i * 3 + j] = s;
      }
    return r;
  }

  private static double[] appliquer(double[] h, double x, double y) {
    double w = h[6] * x + h[7] * y + h[8];
    if (Math.abs(w) < 1e-9) w = 1e-9;
    return new double[] {(h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w};
  }

  private boolean valide(double[][] q, double[][] ancien) {
    // convexe
    double sg = 0;
    for (int i = 0; i < 4; i++) {
      double[] a = q[i], b = q[(i + 1) % 4], c = q[(i + 2) % 4];
      double cr = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
      if (cr == 0) return false;
      if (sg == 0) sg = Math.signum(cr);
      else if (Math.signum(cr) != sg) return false;
    }
    double aire = 0;
    for (int i = 0; i < 4; i++) {
      double[] a = q[i], b = q[(i + 1) % 4];
      aire += a[0] * b[1] - b[0] * a[1];
    }
    aire = Math.abs(aire) / 2 / ((double) pw * ph);
    if (aire < 0.004 || aire > 3.0) return false;
    double aireAv = 0;
    for (int i = 0; i < 4; i++) {
      double[] a = ancien[i], b = ancien[(i + 1) % 4];
      aireAv += a[0] * b[1] - b[0] * a[1];
    }
    aireAv = Math.abs(aireAv) / 2 / ((double) pw * ph);
    if (aireAv > 0 && (aire / aireAv > 1.6 || aire / aireAv < 0.6)) return false;
    for (int i = 0; i < 4; i++) {
      if (q[i][0] < -pw || q[i][0] > 2 * pw || q[i][1] < -ph || q[i][1] > 2 * ph) return false;
    }
    return true;
  }

  // ---------------------------------------------------------------- flux optique
  private double[] suivre(float[] a, float[] b) {
    // points à suivre : coins bien contrastés dans le plateau
    java.util.ArrayList<float[]> pts = choisirPoints(a);
    derniersPoints = pts.size();
    derniersInliers = 0;
    if (pts.size() < 12) return null;
    float[][] pa = pyramide(a, pw, ph);
    float[][] pb = pyramide(b, pw, ph);
    java.util.ArrayList<double[]> src = new java.util.ArrayList<>();
    java.util.ArrayList<double[]> dst = new java.util.ArrayList<>();
    for (float[] p : pts) {
      double[] r = lk(pa, pb, p[0], p[1]);
      if (r != null) {
        src.add(new double[] {p[0], p[1]});
        dst.add(r);
      }
    }
    if (src.size() < 10) return null;
    return ransac(src, dst);
  }

  private java.util.ArrayList<float[]> choisirPoints(float[] g) {
    int x0 = pw, y0 = ph, x1 = 0, y1 = 0;
    for (double[] c : quadPx) {
      x0 = Math.min(x0, (int) Math.floor(c[0]));
      y0 = Math.min(y0, (int) Math.floor(c[1]));
      x1 = Math.max(x1, (int) Math.ceil(c[0]));
      y1 = Math.max(y1, (int) Math.ceil(c[1]));
    }
    x0 = Math.max(x0, 8);
    y0 = Math.max(y0, 8);
    x1 = Math.min(x1, pw - 9);
    y1 = Math.min(y1, ph - 9);
    java.util.ArrayList<float[]> res = new java.util.ArrayList<>();
    if (x1 - x0 < 16 || y1 - y0 < 16) return res;
    int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
    float[] ixx = new float[bw * bh], iyy = new float[bw * bh], ixy = new float[bw * bh];
    for (int j = 0; j < bh; j++)
      for (int i = 0; i < bw; i++) {
        int x = x0 + i, y = y0 + j;
        float gx = (g[y * pw + x + 1] - g[y * pw + x - 1]) * 0.5f;
        float gy = (g[(y + 1) * pw + x] - g[(y - 1) * pw + x]) * 0.5f;
        ixx[j * bw + i] = gx * gx;
        iyy[j * bw + i] = gy * gy;
        ixy[j * bw + i] = gx * gy;
      }
    float[] sxx = boite(ixx, bw, bh, 2), syy = boite(iyy, bw, bh, 2), sxy = boite(ixy, bw, bh, 2);
    int cell = 14;
    int nx = (bw + cell - 1) / cell, ny = (bh + cell - 1) / cell;
    float[] best = new float[nx * ny];
    int[] bi = new int[nx * ny];
    java.util.Arrays.fill(bi, -1);
    float maxe = 0;
    float[] eig = new float[bw * bh];
    for (int k = 0; k < eig.length; k++) {
      float a = sxx[k], d = syy[k], bb = sxy[k];
      float tr = a + d, det = a * d - bb * bb;
      float disc = (float) Math.sqrt(Math.max(0, tr * tr / 4 - det));
      eig[k] = tr / 2 - disc;
      if (eig[k] > maxe) maxe = eig[k];
    }
    for (int j = 0; j < bh; j++)
      for (int i = 0; i < bw; i++) {
        float e = eig[j * bw + i];
        if (e < maxe * 0.04f) continue;
        if (!dedans(quadPx, x0 + i, y0 + j, 3)) continue;
        int c = (j / cell) * nx + (i / cell);
        if (e > best[c]) {
          best[c] = e;
          bi[c] = j * bw + i;
        }
      }
    for (int c = 0; c < bi.length; c++)
      if (bi[c] >= 0) res.add(new float[] {x0 + bi[c] % bw, y0 + bi[c] / bw});
    return res;
  }

  private static float[] boite(float[] s, int w, int h, int r) {
    float[] t = new float[w * h], o = new float[w * h];
    for (int j = 0; j < h; j++)
      for (int i = 0; i < w; i++) {
        float v = 0;
        for (int d = -r; d <= r; d++) {
          int ii = Math.min(w - 1, Math.max(0, i + d));
          v += s[j * w + ii];
        }
        t[j * w + i] = v;
      }
    for (int j = 0; j < h; j++)
      for (int i = 0; i < w; i++) {
        float v = 0;
        for (int d = -r; d <= r; d++) {
          int jj = Math.min(h - 1, Math.max(0, j + d));
          v += t[jj * w + i];
        }
        o[j * w + i] = v;
      }
    return o;
  }

  private static boolean dedans(double[][] q, double x, double y, double marge) {
    double sg = 0;
    for (int i = 0; i < 4; i++) {
      double[] a = q[i], b = q[(i + 1) % 4];
      double cr = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
      double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
      if (len < 1e-6) return false;
      cr /= len; // distance signée
      if (sg == 0) sg = Math.signum(cr);
      if (Math.signum(cr) != sg || Math.abs(cr) < marge) return false;
    }
    return true;
  }

  private static float[][] pyramide(float[] g, int w, int h) {
    float[][] p = new float[NIV][];
    p[0] = g;
    int cw = w, ch = h;
    for (int l = 1; l < NIV; l++) {
      int nw = cw / 2, nh = ch / 2;
      float[] s = p[l - 1], d = new float[nw * nh];
      for (int j = 0; j < nh; j++)
        for (int i = 0; i < nw; i++) {
          int o = 2 * j * cw + 2 * i;
          d[j * nw + i] = 0.25f * (s[o] + s[o + 1] + s[o + cw] + s[o + cw + 1]);
        }
      p[l] = d;
      cw = nw;
      ch = nh;
    }
    return p;
  }

  private static float bil(float[] g, int w, int h, double x, double y) {
    int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
    if (xi < 0 || yi < 0 || xi + 1 >= w || yi + 1 >= h) return Float.NaN;
    float fx = (float) (x - xi), fy = (float) (y - yi);
    int o = yi * w + xi;
    return (g[o] * (1 - fx) + g[o + 1] * fx) * (1 - fy) + (g[o + w] * (1 - fx) + g[o + w + 1] * fx) * fy;
  }

  /** Suit un point de a vers b (pyramidal). Renvoie la position dans b ou null. */
  private double[] lk(float[][] pa, float[][] pb, double px, double py) {
    double gx = 0, gy = 0;
    int[] ws = new int[NIV], hs = new int[NIV];
    ws[0] = pw;
    hs[0] = ph;
    for (int l = 1; l < NIV; l++) {
      ws[l] = ws[l - 1] / 2;
      hs[l] = hs[l - 1] / 2;
    }
    int n = 2 * DEMI + 1;
    float[] tmpl = new float[n * n], ddx = new float[n * n], ddy = new float[n * n];
    double err = 0;
    for (int l = NIV - 1; l >= 0; l--) {
      double sc = 1.0 / (1 << l);
      double cx = px * sc, cy = py * sc;
      int w = ws[l], h = hs[l];
      float[] A = pa[l], B = pb[l];
      // modèle et gradients
      double g11 = 0, g12 = 0, g22 = 0;
      boolean ok = true;
      for (int j = -DEMI; j <= DEMI && ok; j++)
        for (int i = -DEMI; i <= DEMI; i++) {
          double x = cx + i, y = cy + j;
          float v = bil(A, w, h, x, y);
          float vx1 = bil(A, w, h, x + 1, y), vx0 = bil(A, w, h, x - 1, y);
          float vy1 = bil(A, w, h, x, y + 1), vy0 = bil(A, w, h, x, y - 1);
          if (Float.isNaN(v) || Float.isNaN(vx1) || Float.isNaN(vx0) || Float.isNaN(vy1) || Float.isNaN(vy0)) {
            ok = false;
            break;
          }
          int k = (j + DEMI) * n + (i + DEMI);
          tmpl[k] = v;
          ddx[k] = (vx1 - vx0) * 0.5f;
          ddy[k] = (vy1 - vy0) * 0.5f;
          g11 += ddx[k] * ddx[k];
          g12 += ddx[k] * ddy[k];
          g22 += ddy[k] * ddy[k];
        }
      if (!ok) return null;
      double det = g11 * g22 - g12 * g12;
      if (det < 1e-3 * n * n) {
        if (l == 0) return null;
        gx *= 2;
        gy *= 2;
        continue;
      }
      double vx = 0, vy = 0;
      for (int it = 0; it < 12; it++) {
        double b1 = 0, b2 = 0, e = 0;
        for (int j = -DEMI; j <= DEMI; j++)
          for (int i = -DEMI; i <= DEMI; i++) {
            int k = (j + DEMI) * n + (i + DEMI);
            float v = bil(B, w, h, cx + i + gx + vx, cy + j + gy + vy);
            if (Float.isNaN(v)) return null;
            float d = tmpl[k] - v;
            b1 += d * ddx[k];
            b2 += d * ddy[k];
            e += Math.abs(d);
          }
        double dx = (g22 * b1 - g12 * b2) / det;
        double dy = (-g12 * b1 + g11 * b2) / det;
        vx += dx;
        vy += dy;
        err = e / (n * n);
        if (dx * dx + dy * dy < 0.0009) break;
      }
      gx += vx;
      gy += vy;
      if (l > 0) {
        gx *= 2;
        gy *= 2;
      }
    }
    if (err > 25) return null;
    double rx = px + gx, ry = py + gy;
    if (rx < 2 || ry < 2 || rx > pw - 3 || ry > ph - 3) return null;
    return new double[] {rx, ry};
  }

  // ---------------------------------------------------------------- homographie robuste
  private double[] ransac(java.util.ArrayList<double[]> src, java.util.ArrayList<double[]> dst) {
    int n = src.size();
    double s = pw / 2.0, cx = pw / 2.0, cy = ph / 2.0;
    double[][] u = new double[n][2], v = new double[n][2];
    for (int i = 0; i < n; i++) {
      u[i][0] = (src.get(i)[0] - cx) / s;
      u[i][1] = (src.get(i)[1] - cy) / s;
      v[i][0] = (dst.get(i)[0] - cx) / s;
      v[i][1] = (dst.get(i)[1] - cy) / s;
    }
    double seuil = 1.6 / s;
    double[] meilleur = null;
    int mi = -1;
    boolean[] best = null;
    int[] idx = new int[4];
    for (int it = 0; it < 150; it++) {
      for (int k = 0; k < 4; k++) {
        boolean ok;
        do {
          idx[k] = (int) (alea() % n);
          ok = true;
          for (int m = 0; m < k; m++) if (idx[m] == idx[k]) ok = false;
        } while (!ok);
      }
      double[][] a = new double[4][], b = new double[4][];
      for (int k = 0; k < 4; k++) {
        a[k] = u[idx[k]];
        b[k] = v[idx[k]];
      }
      double[] hh = homographie4(a, b);
      if (hh == null) continue;
      int c = 0;
      boolean[] in = new boolean[n];
      for (int i = 0; i < n; i++) {
        double[] p = appliquer(hh, u[i][0], u[i][1]);
        if (Math.hypot(p[0] - v[i][0], p[1] - v[i][1]) < seuil) {
          in[i] = true;
          c++;
        }
      }
      if (c > mi) {
        mi = c;
        best = in;
        meilleur = hh;
      }
    }
    if (best == null || mi < 10 || mi < 0.3 * n) {
      derniersInliers = Math.max(mi, 0);
      return null;
    }
    // ajustement sur tous les points concordants (2 passes)
    double[] hh = meilleur;
    for (int pass = 0; pass < 2; pass++) {
      java.util.ArrayList<double[]> a = new java.util.ArrayList<>(), b = new java.util.ArrayList<>();
      for (int i = 0; i < n; i++) {
        double[] p = appliquer(hh, u[i][0], u[i][1]);
        if (Math.hypot(p[0] - v[i][0], p[1] - v[i][1]) < seuil) {
          a.add(u[i]);
          b.add(v[i]);
        }
      }
      if (a.size() < 8) break;
      double[] r = moindresCarres(a, b);
      if (r != null) hh = r;
    }
    int cnt = 0;
    for (int i = 0; i < n; i++) {
      double[] p = appliquer(hh, u[i][0], u[i][1]);
      if (Math.hypot(p[0] - v[i][0], p[1] - v[i][1]) < seuil) cnt++;
    }
    derniersInliers = cnt;
    if (cnt < 10 || cnt < 0.3 * n) return null;
    // retour en pixels : H_px = T^-1 * H * T
    double[] T = {1 / s, 0, -cx / s, 0, 1 / s, -cy / s, 0, 0, 1};
    double[] Ti = {s, 0, cx, 0, s, cy, 0, 0, 1};
    return mul(Ti, mul(hh, T));
  }

  private long alea() {
    graine = graine * 6364136223846793005L + 1442695040888963407L;
    return (graine >>> 33);
  }

  private static double[] homographie4(double[][] a, double[][] b) {
    double[][] m = new double[8][9];
    for (int i = 0; i < 4; i++) {
      double x = a[i][0], y = a[i][1], X = b[i][0], Y = b[i][1];
      m[2 * i] = new double[] {x, y, 1, 0, 0, 0, -X * x, -X * y, X};
      m[2 * i + 1] = new double[] {0, 0, 0, x, y, 1, -Y * x, -Y * y, Y};
    }
    double[] r = gauss(m, 8);
    if (r == null) return null;
    return new double[] {r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], 1};
  }

  private static double[] moindresCarres(java.util.ArrayList<double[]> a, java.util.ArrayList<double[]> b) {
    double[][] m = new double[8][9];
    for (int i = 0; i < a.size(); i++) {
      double x = a.get(i)[0], y = a.get(i)[1], X = b.get(i)[0], Y = b.get(i)[1];
      double[] r1 = {x, y, 1, 0, 0, 0, -X * x, -X * y, X};
      double[] r2 = {0, 0, 0, x, y, 1, -Y * x, -Y * y, Y};
      for (int p = 0; p < 8; p++)
        for (int q = 0; q < 9; q++) m[p][q] += r1[p] * r1[q] + r2[p] * r2[q];
    }
    double[] r = gauss(m, 8);
    if (r == null) return null;
    return new double[] {r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], 1};
  }

  private static double[] gauss(double[][] m, int n) {
    for (int c = 0; c < n; c++) {
      int p = c;
      for (int r = c + 1; r < n; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
      if (Math.abs(m[p][c]) < 1e-12) return null;
      double[] t = m[c];
      m[c] = m[p];
      m[p] = t;
      for (int r = 0; r < n; r++) {
        if (r == c) continue;
        double f = m[r][c] / m[c][c];
        for (int k = c; k <= n; k++) m[r][k] -= f * m[c][k];
      }
    }
    double[] x = new double[n];
    for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
    return x;
  }

  /** Initialise le suivi à partir de coins normalisés (sans image de référence : le premier frame() posera la base). */
  public synchronized void demarrer(double[][] q) {
    if (prev == null) {
      quadPx = null;
      return;
    }
    ancre(id, q);
  }
}
