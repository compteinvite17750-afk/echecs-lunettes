package fr.echecs.lunettes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repérage de l'échiquier par son quadrillage : on cherche les croisements noir/blanc ("selles") puis
 * on ajuste un réseau régulier (homographie) dessus. Les pièces ne gênent presque pas : il suffit de voir
 * une bonne partie des croisements. Le score (nombre de croisements reconnus) dit si le résultat est fiable :
 * 60 et plus = sûr, moins de 40 = faux.
 */
public final class Lattice {
    private Lattice() {}

    public static final class Res {
        /** Les 4 coins du plateau (en pixels de l'image grise), dans l'ordre du réseau. */
        public final double[][] px;
        /** Nombre de croisements reconnus dans le plateau (moins ceux reconnus dehors). */
        public final double v;
        Res(double[][] p, double v) { px = p; this.v = v; }
    }

    public static final double SEUIL = 45;

    // ------------------------------------------------------------------ croisements
    static double[][] selles(Coins.Gris g, double sig) {
        int w = g.w, h = g.h;
        int R = (int) Math.ceil(3 * sig);
        double[] k = new double[2 * R + 1];
        double s = 0;
        for (int i = -R; i <= R; i++) { k[i + R] = Math.exp(-i * i / (2 * sig * sig)); s += k[i + R]; }
        for (int i = 0; i < k.length; i++) k[i] /= s;
        float[] t = new float[w * h];
        float[] b = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double a = 0;
                for (int i = -R; i <= R; i++) {
                    int xx = Math.min(w - 1, Math.max(0, x + i));
                    a += k[i + R] * g.p[y * w + xx];
                }
                t[y * w + x] = (float) a;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double a = 0;
                for (int i = -R; i <= R; i++) {
                    int yy = Math.min(h - 1, Math.max(0, y + i));
                    a += k[i + R] * t[yy * w + x];
                }
                b[y * w + x] = (float) a;
            }
        }
        float[] r = new float[w * h];
        double mx = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int o = y * w + x;
                double lxx = 0, lyy = 0, lxy = 0;
                // Sobel 3x3 : dxx = [1 -2 1] x [1 2 1]^T ; dyy = [1 2 1] x [1 -2 1]^T ; dxy = [-1 0 1] x [-1 0 1]^T
                double a00 = b[o - w - 1], a01 = b[o - w], a02 = b[o - w + 1];
                double a10 = b[o - 1], a11 = b[o], a12 = b[o + 1];
                double a20 = b[o + w - 1], a21 = b[o + w], a22 = b[o + w + 1];
                lxx = (a00 - 2 * a01 + a02) + 2 * (a10 - 2 * a11 + a12) + (a20 - 2 * a21 + a22);
                lyy = (a00 - 2 * a10 + a20) + 2 * (a01 - 2 * a11 + a21) + (a02 - 2 * a12 + a22);
                lxy = (a22 - a20 - a02 + a00);
                double v = lxy * lxy - lxx * lyy;
                r[o] = (float) v;
                if (v > mx) mx = v;
            }
        }
        double thr = Math.max(mx * 0.15, 1e-6);
        List<double[]> out = new ArrayList<>();
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                float v = r[y * w + x];
                if (v <= thr) continue;
                boolean max = true;
                for (int dy = -2; dy <= 2 && max; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        if (r[(y + dy) * w + x + dx] > v) { max = false; break; }
                if (max) out.add(new double[]{x, y});
            }
        }
        // les égalités (plateaux) donneraient des doublons : on les fusionne
        List<double[]> fin = new ArrayList<>();
        for (double[] p : out) {
            boolean dup = false;
            for (double[] q : fin) if (Math.abs(p[0] - q[0]) <= 2 && Math.abs(p[1] - q[1]) <= 2) { dup = true; break; }
            if (!dup) fin.add(p);
        }
        return fin.toArray(new double[0][]);
    }

    // ------------------------------------------------------------------ homographie moindres carrés
    /** Homographie réseau (i,j) -> pixels, ajustée sur les points idx. Retourne 9 coefficients ou null. */
    static double[] ajuste(double[][] ij, double[][] xy, int[] idx) {
        int n = idx.length;
        if (n < 4) return null;
        double ax = 0, ay = 0, bx = 0, by = 0;
        for (int k : idx) { ax += ij[k][0]; ay += ij[k][1]; bx += xy[k][0]; by += xy[k][1]; }
        ax /= n; ay /= n; bx /= n; by /= n;
        double da = 0, db = 0;
        for (int k : idx) {
            da += Math.hypot(ij[k][0] - ax, ij[k][1] - ay);
            db += Math.hypot(xy[k][0] - bx, xy[k][1] - by);
        }
        da /= n; db /= n;
        if (da < 1e-9 || db < 1e-9) return null;
        double sa = Math.sqrt(2) / da, sb = Math.sqrt(2) / db;
        double[][] M = new double[8][9];
        for (int k : idx) {
            double x = (ij[k][0] - ax) * sa, y = (ij[k][1] - ay) * sa;
            double u = (xy[k][0] - bx) * sb, v = (xy[k][1] - by) * sb;
            double[] r1 = {x, y, 1, 0, 0, 0, -u * x, -u * y};
            double[] r2 = {0, 0, 0, x, y, 1, -v * x, -v * y};
            for (int p = 0; p < 8; p++)
                for (int q = 0; q < 8; q++) { M[p][q] += r1[p] * r1[q] + r2[p] * r2[q]; }
            for (int p = 0; p < 8; p++) M[p][8] += r1[p] * u + r2[p] * v;
        }
        for (int c = 0; c < 8; c++) {
            int piv = c;
            for (int r = c + 1; r < 8; r++) if (Math.abs(M[r][c]) > Math.abs(M[piv][c])) piv = r;
            if (Math.abs(M[piv][c]) < 1e-12) return null;
            double[] tmp = M[c]; M[c] = M[piv]; M[piv] = tmp;
            for (int r = 0; r < 8; r++) {
                if (r == c) continue;
                double f = M[r][c] / M[c][c];
                if (f == 0) continue;
                for (int q = c; q < 9; q++) M[r][q] -= f * M[c][q];
            }
        }
        double[] hn = new double[9];
        for (int i = 0; i < 8; i++) hn[i] = M[i][8] / M[i][i];
        hn[8] = 1;
        // H = Tb^-1 * Hn * Ta   avec Ta : (x-ax)*sa ; Tb^-1 : u/sb + bx
        double[][] Hn = {{hn[0], hn[1], hn[2]}, {hn[3], hn[4], hn[5]}, {hn[6], hn[7], hn[8]}};
        double[][] Ta = {{sa, 0, -ax * sa}, {0, sa, -ay * sa}, {0, 0, 1}};
        double[][] Tbi = {{1 / sb, 0, bx}, {0, 1 / sb, by}, {0, 0, 1}};
        double[][] H = mul(Tbi, mul(Hn, Ta));
        double f = H[2][2];
        if (Math.abs(f) < 1e-12) return null;
        double[] o = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) o[i * 3 + j] = H[i][j] / f;
        for (double d : o) if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        return o;
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) c[i][j] += a[i][k] * b[k][j];
        return c;
    }

    static double[] inverse(double[] h) {
        double a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7], i = h[8];
        double A = e * i - f * hh, B = -(d * i - f * g), C = d * hh - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-18) return null;
        double[] r = {
            A, -(b * i - c * hh), b * f - c * e,
            B, a * i - c * g, -(a * f - c * d),
            C, -(a * hh - b * g), a * e - b * d};
        for (int k = 0; k < 9; k++) r[k] /= det;
        return r;
    }

    static double[] proj(double[] h, double x, double y) {
        double z = h[6] * x + h[7] * y + h[8];
        return new double[]{(h[0] * x + h[1] * y + h[2]) / z, (h[3] * x + h[4] * y + h[5]) / z};
    }

    /** Position (i,j) dans le réseau de tous les points ; null si impossible. */
    static double[][] enReseau(double[] h, double[][] P) {
        double[] hi = inverse(h);
        if (hi == null) return null;
        double[][] r = new double[P.length][];
        for (int k = 0; k < P.length; k++) {
            r[k] = proj(hi, P[k][0], P[k][1]);
            if (Double.isNaN(r[k][0]) || Double.isInfinite(r[k][0]) || Double.isNaN(r[k][1]) || Double.isInfinite(r[k][1])) return null;
        }
        return r;
    }

    static double cellule(double[] h) {
        double[] p0 = proj(h, 4, 4), p1 = proj(h, 5, 4), p2 = proj(h, 4, 5);
        double ux = p1[0] - p0[0], uy = p1[1] - p0[1], vx = p2[0] - p0[0], vy = p2[1] - p0[1];
        return Math.sqrt(Math.abs(ux * vy - uy * vx));
    }

    private static int[] sous(int[] a, boolean[] keep) {
        int n = 0;
        for (boolean b : keep) if (b) n++;
        int[] o = new int[n];
        int j = 0;
        for (int i = 0; i < a.length; i++) if (keep[i]) o[j++] = a[i];
        return o;
    }

    /** Ajuste en écartant les points trop loin (résidu). Retourne {H, indices} ou null. */
    private static Object[] ajusteRobuste(double[][] r, double[][] P, int[] k0) {
        int[] ks = k0;
        double[] H = ajuste(r, P, ks);
        if (H == null) return null;
        for (int it = 0; it < 3; it++) {
            double[] e = new double[ks.length];
            for (int i = 0; i < ks.length; i++) {
                double[] p = proj(H, r[ks[i]][0], r[ks[i]][1]);
                e[i] = Math.hypot(p[0] - P[ks[i]][0], p[1] - P[ks[i]][1]);
            }
            double thr = Math.max(1.5, 0.1 * cellule(H));
            double[] s = e.clone();
            Arrays.sort(s);
            double q = s[Math.min(s.length - 1, (int) (s.length * 0.6))];
            boolean[] keep = new boolean[ks.length];
            int nk = 0;
            for (int i = 0; i < ks.length; i++) {
                keep[i] = e[i] <= Math.max(thr, q) && e[i] <= 3 * thr;
                if (keep[i]) nk++;
            }
            if (nk < 6) return null;
            ks = sous(ks, keep);
            H = ajuste(r, P, ks);
            if (H == null) return null;
        }
        return new Object[]{H, ks};
    }

    /** Résultat d'une croissance : H, étiquettes (arrondies) et marque des points retenus (un seul par nœud). */
    private static final class Pousse {
        double[] H;
        double[][] r;
        boolean[] ok;
    }

    private static Pousse pousse(double[][] P, double[] H0, double tol, int seed, int maxspan) {
        double[] H = H0;
        double lo0 = -seed, lo1 = -seed, hi0 = seed, hi1 = seed;
        for (int it = 0; it < 12; it++) {
            double[][] ij = enReseau(H, P);
            if (ij == null) return null;
            Map<Long, Integer> lab = new HashMap<>();
            double[] dd = new double[P.length];
            double[][] r = new double[P.length][2];
            for (int k = 0; k < P.length; k++) {
                r[k][0] = Math.rint(ij[k][0]);
                r[k][1] = Math.rint(ij[k][1]);
                dd[k] = Math.max(Math.abs(ij[k][0] - r[k][0]), Math.abs(ij[k][1] - r[k][1]));
                if (dd[k] < tol && r[k][0] >= lo0 - 1 && r[k][0] <= hi0 + 1 && r[k][1] >= lo1 - 1 && r[k][1] <= hi1 + 1) {
                    long key = ((long) (int) r[k][0] + 1000) * 100000L + ((int) r[k][1] + 1000);
                    Integer o = lab.get(key);
                    if (o == null || dd[k] < dd[o]) lab.put(key, k);
                }
            }
            if (lab.size() < 6) return null;
            int[] k0 = new int[lab.size()];
            int z = 0;
            for (int v : lab.values()) k0[z++] = v;
            Object[] fit = ajusteRobuste(r, P, k0);
            if (fit == null) return null;
            double[] H2 = (double[]) fit[0];
            int[] ks = (int[]) fit[1];
            double n0 = 1e9, n1 = 1e9, m0 = -1e9, m1 = -1e9;
            for (int k : ks) {
                n0 = Math.min(n0, r[k][0]); n1 = Math.min(n1, r[k][1]);
                m0 = Math.max(m0, r[k][0]); m1 = Math.max(m1, r[k][1]);
            }
            if (Math.max(m0 - n0, m1 - n1) > maxspan) {
                double c0 = (m0 + n0) / 2, c1 = (m1 + n1) / 2;
                n0 = Math.max(n0, c0 - maxspan / 2.0); m0 = Math.min(m0, c0 + maxspan / 2.0);
                n1 = Math.max(n1, c1 - maxspan / 2.0); m1 = Math.min(m1, c1 + maxspan / 2.0);
            }
            boolean same = n0 == lo0 && n1 == lo1 && m0 == hi0 && m1 == hi1;
            H = H2;
            lo0 = n0; lo1 = n1; hi0 = m0; hi1 = m1;
            if (same && it > 3) break;
        }
        double[][] ij = enReseau(H, P);
        if (ij == null) return null;
        double thr = Math.max(1.5, 0.1 * cellule(H));
        Map<Long, Integer> best = new HashMap<>();
        double[][] r = new double[P.length][2];
        double[] e = new double[P.length];
        boolean[] okk = new boolean[P.length];
        for (int k = 0; k < P.length; k++) {
            r[k][0] = Math.rint(ij[k][0]);
            r[k][1] = Math.rint(ij[k][1]);
            double d = Math.max(Math.abs(ij[k][0] - r[k][0]), Math.abs(ij[k][1] - r[k][1]));
            double[] p = proj(H, r[k][0], r[k][1]);
            e[k] = Math.hypot(p[0] - P[k][0], p[1] - P[k][1]);
            if (d < tol && e[k] <= 2 * thr && r[k][0] >= lo0 - 1 && r[k][0] <= hi0 + 1 && r[k][1] >= lo1 - 1 && r[k][1] <= hi1 + 1) {
                long key = ((long) (int) r[k][0] + 1000) * 100000L + ((int) r[k][1] + 1000);
                Integer o = best.get(key);
                if (o == null || e[k] < e[o]) best.put(key, k);
            }
        }
        for (int v : best.values()) okk[v] = true;
        Pousse res = new Pousse();
        res.H = H; res.r = r; res.ok = okk;
        return res;
    }

    // ------------------------------------------------------------------ fenêtre 8x8
    /** {valeur, oi, oj, dedans, dehors} */
    private static List<int[]> fenetres(double[][] r, boolean[] ok) {
        int n = 0;
        int[][] G = new int[80][80];
        for (int k = 0; k < r.length; k++) {
            if (!ok[k]) continue;
            n++;
            int a = (int) r[k][0] + 40, b = (int) r[k][1] + 40;
            if (a >= 0 && a < 80 && b >= 0 && b < 80) G[a][b]++;
        }
        if (n == 0) return new ArrayList<>();
        int[][] C = new int[81][81];
        for (int i = 0; i < 80; i++)
            for (int j = 0; j < 80; j++) C[i + 1][j + 1] = G[i][j] + C[i][j + 1] + C[i + 1][j] - C[i][j];
        List<int[]> L = new ArrayList<>();
        for (int oi = -12; oi < 12; oi++) {
            for (int oj = -12; oj < 12; oj++) {
                int i0 = oi + 40, j0 = oj + 40;
                int ins = C[i0 + 9][j0 + 9] - C[i0][j0 + 9] - C[i0 + 9][j0] + C[i0][j0];
                L.add(new int[]{ins - (n - ins), oi, oj, ins, n - ins});
            }
        }
        L.sort((x, y) -> y[0] != x[0] ? y[0] - x[0] : (x[1] != y[1] ? y[1] - x[1] : y[2] - x[2]));
        int best = L.get(0)[0];
        List<int[]> out = new ArrayList<>();
        for (int[] w : L) if (w[0] >= best - 10 && out.size() < 12) out.add(w);
        return out;
    }

    /** Changements de base entiers (déterminant +-1) : un même réseau peut être étiqueté de travers. */
    private static final int[][] UNI = unimodulaires();

    private static int[][] unimodulaires() {
        List<int[]> l = new ArrayList<>();
        for (int a = -1; a <= 1; a++)
            for (int b = -1; b <= 1; b++)
                for (int c = -1; c <= 1; c++)
                    for (int d = -1; d <= 1; d++) if (Math.abs(a * d - b * c) == 1) l.add(new int[]{a, b, c, d});
        return l.toArray(new int[0][]);
    }

    private static double[][] coinsDe(double[] H, int oi, int oj) {
        return new double[][]{proj(H, oi, oj), proj(H, oi + 8, oj), proj(H, oi + 8, oj + 8), proj(H, oi, oj + 8)};
    }

    private static boolean sain(double[] H, int oi, int oj, int w, int h) {
        double[][] q = coinsDe(H, oi, oj);
        for (double[] p : q) if (Double.isNaN(p[0]) || Double.isNaN(p[1]) || Double.isInfinite(p[0]) || Double.isInfinite(p[1])) return false;
        int pos = 0, neg = 0;
        for (int k = 0; k < 4; k++) {
            double ax = q[(k + 1) % 4][0] - q[k][0], ay = q[(k + 1) % 4][1] - q[k][1];
            double bx = q[(k + 2) % 4][0] - q[(k + 1) % 4][0], by = q[(k + 2) % 4][1] - q[(k + 1) % 4][1];
            double c = ax * by - ay * bx;
            if (c > 0) pos++; else neg++;
        }
        if (pos != 4 && neg != 4) return false;
        double[] e = new double[4];
        double mn = 1e18, mx = 0;
        for (int k = 0; k < 4; k++) {
            e[k] = Math.hypot(q[(k + 1) % 4][0] - q[k][0], q[(k + 1) % 4][1] - q[k][1]);
            mn = Math.min(mn, e[k]); mx = Math.max(mx, e[k]);
        }
        if (mx / Math.max(mn, 1e-6) > 5) return false;
        if (Math.max(e[0], e[2]) / Math.max(Math.min(e[0], e[2]), 1e-6) > 3.5) return false;
        if (Math.max(e[1], e[3]) / Math.max(Math.min(e[1], e[3]), 1e-6) > 3.5) return false;
        for (double[] p : q) if (p[0] < -0.3 * w || p[0] > 1.3 * w || p[1] < -0.3 * h || p[1] > 1.3 * h) return false;
        return true;
    }

    private static double scorePieds(double[] H, int oi, int oj, List<double[]> pieds) {
        if (pieds == null || pieds.isEmpty()) return 0;
        double[] hi = inverse(H);
        if (hi == null) return -50;
        int dedans = 0;
        for (double[] f : pieds) {
            double[] p = proj(hi, f[0], f[1]);
            if (Double.isNaN(p[0]) || Double.isNaN(p[1])) return -50;
            if (p[0] >= oi - 0.5 && p[0] <= oi + 8.5 && p[1] >= oj - 0.5 && p[1] <= oj + 8.5) dedans++;
        }
        return 50.0 * dedans / pieds.size();
    }

    // ------------------------------------------------------------------ recherche
    /**
     * Cherche l'échiquier. pieds : pieds des pièces détectées (pixels de l'image grise), peut être vide.
     * Retourne null si rien de crédible.
     */
    public static Res trouve(Coins.Gris g, List<double[]> pieds) {
        double sig = Math.max(1.2, 2.0 * g.w / 1000.0);
        double[][] P = selles(g, sig);
        int n = P.length;
        if (n < 12) return null;
        int kk = Math.min(7, n);
        int[][] nn = new int[n][kk];
        for (int p = 0; p < n; p++) {
            final int pp = p;
            Integer[] ord = new Integer[n];
            for (int i = 0; i < n; i++) ord[i] = i;
            final double[] pd = new double[n];
            for (int i = 0; i < n; i++) pd[i] = Math.hypot(P[i][0] - P[pp][0], P[i][1] - P[pp][1]);
            Arrays.sort(ord, (a, b) -> Double.compare(pd[a], pd[b]));
            for (int i = 0; i < kk; i++) nn[p][i] = ord[i];
        }
        List<int[]> hyp = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            for (int ia = 1; ia < kk; ia++) {
                int a = nn[p][ia];
                double vax = P[a][0] - P[p][0], vay = P[a][1] - P[p][1];
                double la = Math.hypot(vax, vay);
                for (int ib = ia + 1; ib < kk; ib++) {
                    int b = nn[p][ib];
                    double vbx = P[b][0] - P[p][0], vby = P[b][1] - P[p][1];
                    double lb = Math.hypot(vbx, vby);
                    double rap = la / lb;
                    if (!(rap > 0.5 && rap < 2)) continue;
                    double cr = Math.abs(vax * vby - vay * vbx) / (la * lb);
                    if (cr < 0.5) continue;
                    int cnt = comptePatch(P, p, vax, vay, vbx, vby, null);
                    hyp.add(new int[]{cnt, p, a, b});
                }
            }
        }
        hyp.sort((x, y) -> y[0] - x[0]);
        double bestSc = -1e18;
        double bestV = 0;
        double[][] bestQ = null;
        int tries = Math.min(40, hyp.size());
        for (int t = 0; t < tries; t++) {
            int[] hh = hyp.get(t);
            int p = hh[1], a = hh[2], b = hh[3];
            double vax = P[a][0] - P[p][0], vay = P[a][1] - P[p][1];
            double vbx = P[b][0] - P[p][0], vby = P[b][1] - P[p][1];
            List<Integer> idx = new ArrayList<>();
            double[][] lab = new double[n][];
            comptePatch(P, p, vax, vay, vbx, vby, lab);
            for (int k = 0; k < n; k++) if (lab[k] != null) idx.add(k);
            if (idx.size() < 6) continue;
            double[][] ijs = new double[n][];
            int[] ids = new int[idx.size()];
            for (int i = 0; i < idx.size(); i++) { ids[i] = idx.get(i); ijs[ids[i]] = lab[ids[i]]; }
            double[] H0 = ajuste(ijs, P, ids);
            if (H0 == null) continue;
            Pousse ps = pousse(P, H0, 0.15, 3, 9);
            if (ps == null) continue;
            for (int[] M : UNI) {
                int det = M[0] * M[3] - M[1] * M[2];
                // H' = H * [[a b 0][c d 0][0 0 1]] ; étiquettes r' = M^-1 r
                double[] Hv = {
                    ps.H[0] * M[0] + ps.H[1] * M[2], ps.H[0] * M[1] + ps.H[1] * M[3], ps.H[2],
                    ps.H[3] * M[0] + ps.H[4] * M[2], ps.H[3] * M[1] + ps.H[4] * M[3], ps.H[5],
                    ps.H[6] * M[0] + ps.H[7] * M[2], ps.H[6] * M[1] + ps.H[7] * M[3], ps.H[8]};
                double[][] rv = new double[n][2];
                for (int k = 0; k < n; k++) {
                    rv[k][0] = (M[3] * ps.r[k][0] - M[1] * ps.r[k][1]) / det;
                    rv[k][1] = (-M[2] * ps.r[k][0] + M[0] * ps.r[k][1]) / det;
                }
                Pousse cur = new Pousse();
                cur.H = Hv; cur.r = rv; cur.ok = ps.ok;
                // sous-réseau diagonal (un croisement sur deux) ?
                double[][] ij = enReseau(cur.H, P);
                if (ij != null) {
                    int half = 0, okn = 0;
                    for (int k = 0; k < n; k++) {
                        if (cur.ok[k]) okn++;
                        double fx = Math.abs(ij[k][0] - Math.floor(ij[k][0]) - 0.5), fy = Math.abs(ij[k][1] - Math.floor(ij[k][1]) - 0.5);
                        if (Math.max(fx, fy) < 0.12 && Math.max(Math.abs(ij[k][0]), Math.abs(ij[k][1])) < 8) half++;
                    }
                    if (half >= 0.75 * okn) {
                        double[] hv = {
                            cur.H[0] * 0.5 + cur.H[1] * 0.5, cur.H[0] * 0.5 - cur.H[1] * 0.5, cur.H[2],
                            cur.H[3] * 0.5 + cur.H[4] * 0.5, cur.H[3] * 0.5 - cur.H[4] * 0.5, cur.H[5],
                            cur.H[6] * 0.5 + cur.H[7] * 0.5, cur.H[6] * 0.5 - cur.H[7] * 0.5, cur.H[8]};
                        cur = pousse(P, hv, 0.15, 5, 9);
                        if (cur == null) continue;
                    }
                }
                for (int[] w : fenetres(cur.r, cur.ok)) {
                    if (!sain(cur.H, w[1], w[2], g.w, g.h)) continue;
                    double sc = w[0] + scorePieds(cur.H, w[1], w[2], pieds);
                    if (sc > bestSc) {
                        bestSc = sc;
                        bestV = w[0];
                        bestQ = coinsDe(cur.H, w[1], w[2]);
                    }
                }
            }
        }
        if (bestQ == null) return null;
        return new Res(bestQ, bestV);
    }

    /** Compte les croisements qui tombent sur le réseau affine local (|i|,|j| <= 3). Si lab != null, y range les étiquettes. */
    private static int comptePatch(double[][] P, int p, double vax, double vay, double vbx, double vby, double[][] lab) {
        double det = vax * vby - vbx * vay;
        if (Math.abs(det) < 1e-12) return 0;
        int cnt = 0;
        for (int k = 0; k < P.length; k++) {
            double dx = P[k][0] - P[p][0], dy = P[k][1] - P[p][1];
            double i = (dx * vby - vbx * dy) / det;
            double j = (vax * dy - vay * dx) / det;
            double ri = Math.rint(i), rj = Math.rint(j);
            double d = Math.max(Math.abs(i - ri), Math.abs(j - rj));
            if (d < 0.2 && Math.max(Math.abs(ri), Math.abs(rj)) <= 3) {
                cnt++;
                if (lab != null) lab[k] = new double[]{ri, rj};
            }
        }
        return cnt;
    }

    // ------------------------------------------------------------------ utilitaires pour l'appli
    /**
     * Met les 4 coins (pixels d'une image w x h) dans l'ordre haut-gauche, haut-droit, bas-droit, bas-gauche
     * de l'image tournée de rot degrés (sens horaire). Retourne des fractions (0..1) de l'image tournée.
     */
    public static double[][] pourRotation(double[][] px, int w, int h, int rot) {
        double[][] q = new double[4][2];
        int W = w, H = h;
        for (int i = 0; i < 4; i++) {
            double x = px[i][0], y = px[i][1];
            if (rot == 90) { q[i][0] = h - y; q[i][1] = x; }
            else if (rot == 180) { q[i][0] = w - x; q[i][1] = h - y; }
            else if (rot == 270) { q[i][0] = y; q[i][1] = w - x; }
            else { q[i][0] = x; q[i][1] = y; }
        }
        if (rot == 90 || rot == 270) { W = h; H = w; }
        // haut-gauche = coin qui minimise x+y ; puis sens horaire (image : y vers le bas)
        double cx = 0, cy = 0;
        for (double[] p : q) { cx += p[0] / 4; cy += p[1] / 4; }
        Integer[] ord = {0, 1, 2, 3};
        final double fx = cx, fy = cy;
        final double[][] qq = q;
        Arrays.sort(ord, (a, b) -> Double.compare(Math.atan2(qq[a][1] - fy, qq[a][0] - fx), Math.atan2(qq[b][1] - fy, qq[b][0] - fx)));
        // angles croissants = sens horaire à l'écran. On part du coin le plus haut-gauche.
        int deb = 0;
        double best = 1e18;
        for (int i = 0; i < 4; i++) {
            double s = qq[ord[i]][0] + qq[ord[i]][1];
            if (s < best) { best = s; deb = i; }
        }
        double[][] o = new double[4][2];
        for (int i = 0; i < 4; i++) {
            double[] p = qq[ord[(deb + i) % 4]];
            o[i][0] = p[0] / W;
            o[i][1] = p[1] / H;
        }
        return o;
    }
}
