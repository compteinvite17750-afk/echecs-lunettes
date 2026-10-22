package fr.echecs.lunettes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Recherche automatique des 4 coins de l'échiquier dans une image en niveaux de gris.
 * Traduction fidèle de la version Python (suivi.py) qui marche sur les photos des lunettes.
 * Coins : haut-gauche, haut-droit, bas-droit, bas-gauche.
 */
public final class Coins {

    public static final double SEUIL_OK = 0.7;
    static final double POIDS_CADRE = 0.4;
    public static final double[][] SQ ={{0, 0}, {8, 0}, {8, 8}, {0, 8}};

    /** Résultat : coins en fractions de l'image (0..1) et score. */
    public static final class Resultat {
        public final double[][] coins;
        public final double score;
        public Resultat(double[][] c, double s) { coins = c; score = s; }
    }

    /** Image en niveaux de gris (valeurs 0..255). */
    public static final class Gris {
        public final int w, h;
        public final float[] p;
        public Gris(int w, int h, float[] p) { this.w = w; this.h = h; this.p = p; }
    }

    // ---------- points d'échantillonnage ----------
    private static final double[] PU = new double[1024];
    private static final double[] PV = new double[1024];
    private static final double[][][] RING_IN = new double[4][][];
    private static final double[][][] RING_OUT = new double[4][][];

    static {
        double[] off = new double[4];
        for (int i = 0; i < 4; i++) off[i] = (i + 0.5) / 4 * 0.6 + 0.2;
        int k = 0;
        for (int gy = 0; gy < 8; gy++)
            for (int gx = 0; gx < 8; gx++)
                for (int a = 0; a < 4; a++)
                    for (int b = 0; b < 4; b++) {
                        PU[k] = gx + off[b];
                        PV[k] = gy + off[a];
                        k++;
                    }
        for (int i = 0; i < 4; i++) {
            RING_IN[i] = cote(i, -0.5);
            RING_OUT[i] = cote(i, -1.15);
        }
    }

    private static double[][] cote(int i, double d) {
        double[][] r = new double[8][];
        for (int j = 0; j < 8; j++) {
            double a = 0.8 + j * (7.2 - 0.8) / 7.0;
            if (i == 0) r[j] = new double[]{a, d};
            else if (i == 1) r[j] = new double[]{8 - d, a};
            else if (i == 2) r[j] = new double[]{a, 8 - d};
            else r[j] = new double[]{d, a};
        }
        return r;
    }

    // ---------- géométrie ----------
    /** Homographie src -> dst (4 points chacun). Retourne 9 coefficients (ligne par ligne) ou null. */
    public static double[] homographie(double[][] src, double[][] dst) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = src[i][0], y = src[i][1], u = dst[i][0], v = dst[i][1];
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        for (int c = 0; c < 8; c++) {
            int piv = c;
            for (int r = c + 1; r < 8; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
            if (Math.abs(a[piv][c]) < 1e-12) return null;
            double[] t = a[c]; a[c] = a[piv]; a[piv] = t;
            for (int r = 0; r < 8; r++) {
                if (r == c) continue;
                double f = a[r][c] / a[c][c];
                if (f == 0) continue;
                for (int q = c; q < 9; q++) a[r][q] -= f * a[c][q];
            }
        }
        double[] h = new double[9];
        for (int i = 0; i < 8; i++) h[i] = a[i][8] / a[i][i];
        h[8] = 1;
        return h;
    }

    /** Applique l'homographie. Retourne {x, y, z} (z = dénominateur, doit être > 0). */
    public static double[] applique(double[] h, double x, double y) {
        double z = h[6] * x + h[7] * y + h[8];
        return new double[]{(h[0] * x + h[1] * y + h[2]) / z, (h[3] * x + h[4] * y + h[5]) / z, z};
    }

    private static double mediane(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return (n % 2 == 1) ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    // ---------- score d'un quadrilatère ----------
    static double score(Gris L, double sd, double[][] pts, boolean anneau) {
        double[] H = homographie(SQ, pts);
        if (H == null) return -1e9;
        int w = L.w, h = L.h;
        double[] med = new double[64];
        double[] buf = new double[16];
        for (int s = 0; s < 64; s++) {
            for (int j = 0; j < 16; j++) {
                int k = s * 16 + j;
                double u = PU[k], v = PV[k];
                double z = H[6] * u + H[7] * v + H[8];
                if (z <= 0) return -1e9;
                double x = (H[0] * u + H[1] * v + H[2]) / z;
                double y = (H[3] * u + H[4] * v + H[5]) / z;
                if (x < 1 || y < 1 || x > w - 2 || y > h - 2) return -1e9;
                buf[j] = L.p[((int) y) * w + (int) x];
            }
            med[s] = mediane(buf);
        }
        double m = mediane(med);
        double sc = 0;
        for (int s = 0; s < 64; s++) sc += Math.abs(med[s] - m);
        sc = sc / 64 + 1e-6;
        double tot = 0;
        for (int s = 0; s < 64; s++) {
            int gy = s / 8, gx = s % 8;
            double par = ((gx + gy) % 2 == 0) ? 1 : -1;
            double z = (med[s] - m) / sc;
            if (z > 1.5) z = 1.5;
            if (z < -1.5) z = -1.5;
            tot += z * par;
        }
        double res = tot / 64;
        if (anneau && POIDS_CADRE > 0) {
            double[] cs = new double[4];
            double somme = 0, mini = 1e9;
            for (int i = 0; i < 4; i++) {
                Double mi = moy(L, H, RING_IN[i]), mo = moy(L, H, RING_OUT[i]);
                double c;
                if (mi == null || mo == null) c = 0.25;
                else {
                    c = (mo - mi) / sd;
                    if (c < -0.6) c = -0.6;
                    if (c > 0.8) c = 0.8;
                }
                cs[i] = c;
                somme += c;
                mini = Math.min(mini, c);
            }
            res += POIDS_CADRE * (somme / 4 + 0.5 * mini);
        }
        return res;
    }

    private static Double moy(Gris L, double[] H, double[][] R) {
        int w = L.w, h = L.h;
        double tot = 0;
        int n = 0;
        double[] xs = new double[R.length], ys = new double[R.length];
        for (int i = 0; i < R.length; i++) {
            double z = H[6] * R[i][0] + H[7] * R[i][1] + H[8];
            if (z <= 0) return null;
            xs[i] = (H[0] * R[i][0] + H[1] * R[i][1] + H[2]) / z;
            ys[i] = (H[3] * R[i][0] + H[4] * R[i][1] + H[5]) / z;
        }
        for (int i = 0; i < R.length; i++) {
            double x = xs[i], y = ys[i];
            if (x > 1 && x < w - 2 && y > 1 && y < h - 2) {
                tot += L.p[((int) y) * w + (int) x];
                n++;
            }
        }
        if (n < 5) return null;
        return tot / n;
    }

    static boolean quadOk(double[][] p) {
        int pos = 0, neg = 0;
        for (int i = 0; i < 4; i++) {
            double[] a = p[i], b = p[(i + 1) % 4], c = p[(i + 2) % 4];
            double s = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
            if (s > 0) pos++;
            else if (s < 0) neg++;
        }
        return pos == 4 || neg == 4;
    }

    private static double[][] copie(double[][] p) {
        double[][] r = new double[4][];
        for (int i = 0; i < 4; i++) r[i] = p[i].clone();
        return r;
    }

    static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};

    static Resultat raffine(Gris L, double sd, double[][] depart, int[] pas, int tours) {
        double[][] pts = copie(depart);
        double best = score(L, sd, pts, true);
        for (int p : pas) {
            for (int t = 0; t < tours; t++) {
                boolean ameliore = false;
                for (int i = 0; i < 4; i++) {
                    for (int[] d : DIRS) {
                        double[][] nn = copie(pts);
                        nn[i][0] += d[0] * p;
                        nn[i][1] += d[1] * p;
                        if (!quadOk(nn)) continue;
                        double s = score(L, sd, nn, true);
                        if (s > best + 1e-6) {
                            best = s;
                            pts = nn;
                            ameliore = true;
                        }
                    }
                }
                if (!ameliore) break;
            }
        }
        return new Resultat(pts, best);
    }

    // ---------- préparation de l'image ----------
    /** Flou "boîte" 3x3 avec bords répliqués (comme la version Python). */
    static Gris flou3(Gris L) {
        int w = L.w, h = L.h;
        float[] t = new float[w * h], o = new float[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - 1), y1 = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++)
                t[y * w + x] = (L.p[y0 * w + x] + L.p[y * w + x] + L.p[y1 * w + x]) / 3f;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 1), x1 = Math.min(w - 1, x + 1);
                o[y * w + x] = (t[y * w + x0] + t[y * w + x] + t[y * w + x1]) / 3f;
            }
        }
        return new Gris(w, h, o);
    }

    static double ecartType(Gris L) {
        double s = 0, s2 = 0;
        for (float v : L.p) { s += v; s2 += (double) v * v; }
        double m = s / L.p.length;
        return Math.sqrt(Math.max(0, s2 / L.p.length - m * m)) + 1e-9;
    }

    private static double[] arange(double a, double b, double pas) {
        int n = (int) Math.ceil((b - a) / pas);
        double[] r = new double[Math.max(0, n)];
        for (int i = 0; i < r.length; i++) r[i] = a + i * pas;
        return r;
    }

    private static double distMax(double[][] p, double[][] q) {
        double m = 0;
        for (int i = 0; i < 4; i++) m = Math.max(m, Math.abs(p[i][0] - q[i][0]) + Math.abs(p[i][1] - q[i][1]));
        return m;
    }

    private static final class Cand {
        final double s;
        final double[][] p;
        Cand(double s, double[][] p) { this.s = s; this.p = p; }
    }

    /** Recherche sans aucune indication : balaye positions et tailles, puis variantes inclinées. */
    static List<double[][]> rechercheGrossiere(Gris Ls, double sd, int garder) {
        int h = Ls.h, w = Ls.w;
        List<Cand> cands = new ArrayList<>();
        for (double fb : arange(0.28, 0.80, 0.06)) {
            double B = fb * w;
            for (double r : new double[]{0.55, 0.7, 0.85, 1.0}) {
                double T = B * r;
                for (double fh : arange(0.45, 0.95, 0.1)) {
                    double hh = fh * B;
                    for (double fcx : arange(0.2, 0.85, 0.05)) {
                        double cx = fcx * w;
                        for (double fby : arange(0.5, 0.97, 0.05)) {
                            double by = fby * h;
                            double ty = by - hh;
                            if (ty < 2) continue;
                            double[][] p = {{cx - T / 2, ty}, {cx + T / 2, ty}, {cx + B / 2, by}, {cx - B / 2, by}};
                            cands.add(new Cand(score(Ls, sd, p, true), p));
                        }
                    }
                }
            }
        }
        cands.sort((x, y) -> Double.compare(y.s, x.s));
        List<Cand> base = new ArrayList<>();
        for (Cand c : cands) {
            boolean loin = true;
            for (Cand q : base) if (distMax(c.p, q.p) <= 30) { loin = false; break; }
            if (loin) base.add(c);
            if (base.size() >= 40) break;
        }
        List<Cand> var = new ArrayList<>();
        double[] shs = {-0.3, -0.15, 0.0, 0.15, 0.3};
        double[] ros = {-0.15, -0.075, 0.0, 0.075, 0.15};
        for (Cand c : base) {
            double[][] p = c.p;
            double wd = p[2][0] - p[3][0];
            for (double sh : shs) {
                for (double ro : ros) {
                    double[][] q = {
                            {p[0][0] + sh * wd, p[0][1] - ro * wd},
                            {p[1][0] + sh * wd, p[1][1] + ro * wd},
                            {p[2][0], p[2][1] + ro * wd},
                            {p[3][0], p[3][1] - ro * wd}};
                    if (quadOk(q)) var.add(new Cand(score(Ls, sd, q, true), q));
                }
            }
        }
        var.sort((x, y) -> Double.compare(y.s, x.s));
        List<double[][]> out = new ArrayList<>();
        List<Cand> gardes = new ArrayList<>();
        for (Cand c : var) {
            boolean loin = true;
            for (Cand q : gardes) if (distMax(c.p, q.p) <= 25) { loin = false; break; }
            if (loin) { gardes.add(c); out.add(c.p); }
            if (out.size() >= garder) break;
        }
        return out;
    }

    static double[][] decale(double[][] pts, int dx, int dy) {
        double[] H = homographie(SQ, pts);
        if (H == null) return null;
        double[][] uv = {{dx, dy}, {8 + dx, dy}, {8 + dx, 8 + dy}, {dx, 8 + dy}};
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] q = applique(H, uv[i][0], uv[i][1]);
            out[i] = new double[]{q[0], q[1]};
        }
        return out;
    }

    // poses typiques (photos prises assis, lunettes sur le nez)
    static final double[][][] PRESETS = {
            {{0.318, 0.599}, {0.572, 0.613}, {0.620, 0.903}, {0.230, 0.871}},
            {{0.250, 0.499}, {0.582, 0.499}, {0.676, 0.990}, {0.124, 0.950}},
            {{0.342, 0.578}, {0.596, 0.623}, {0.580, 0.914}, {0.198, 0.829}},
            {{0.280, 0.585}, {0.560, 0.556}, {0.660, 0.847}, {0.280, 0.900}},
            {{0.320, 0.472}, {0.582, 0.492}, {0.623, 0.780}, {0.203, 0.783}},
    };

    /**
     * L : image en gris déjà réduite à 500 pixels de large. init : coins précédents (fractions) ou null.
     */
    public static Resultat trouve(Gris L, double[][] init) {
        int w = L.w, h = L.h;
        Gris Ls = flou3(L);
        double sd = ecartType(Ls);
        int[] pas = {16, 8, 4, 2, 1};

        List<double[][]> starts = new ArrayList<>();
        if (init != null) starts.add(versPixels(init, w, h));
        for (double[][] p : PRESETS) starts.add(versPixels(p, w, h));
        Resultat best = meilleur(Ls, sd, starts, pas);

        if (best.score < SEUIL_OK + 0.6) {
            Resultat b2 = meilleur(Ls, sd, rechercheGrossiere(Ls, sd, 16), pas);
            if (b2.score > best.score) best = b2;
        }
        Resultat cand = best;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx == 0 && dy == 0) continue;
                double[][] q = decale(best.coins, dx, dy);
                if (q == null || !quadOk(q)) continue;
                Resultat r = raffine(Ls, sd, q, new int[]{8, 4, 2, 1}, 6);
                if (r.score > cand.score + 0.05) cand = r;
            }
        }
        double[][] f = new double[4][];
        for (int i = 0; i < 4; i++) f[i] = new double[]{cand.coins[i][0] / w, cand.coins[i][1] / h};
        return new Resultat(f, cand.score);
    }

    private static Resultat meilleur(Gris Ls, double sd, List<double[][]> starts, int[] pas) {
        Resultat best = new Resultat(null, -1e9);
        for (double[][] p : starts) {
            Resultat r = raffine(Ls, sd, p, pas, 6);
            if (r.score > best.score) best = r;
        }
        if (best.coins == null) best = new Resultat(starts.get(0), -1e9);
        return best;
    }

    private static double[][] versPixels(double[][] f, int w, int h) {
        double[][] r = new double[4][];
        for (int i = 0; i < 4; i++) r[i] = new double[]{f[i][0] * w, f[i][1] * h};
        return r;
    }
}
