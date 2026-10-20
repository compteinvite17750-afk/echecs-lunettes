package fr.echecs.lunettes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Lecture de l'échiquier à partir des pièces détectées (logique pure, sans Android). */
public final class Lecteur {
    private Lecteur() {}

    public static final String FEN_DEPART = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final char[] TYPES = {'b', 'k', 'n', 'p', 'q', 'r'}; // ordre des classes du modèle

    /** Seuil de luminosité qui sépare les pièces blanches (claires) des noires (sombres). */
    public static double seuil(double[] lums) {
        int n = lums.length;
        if (n == 0) return 110;
        double[] s = lums.clone();
        Arrays.sort(s);
        double[] cum = new double[n + 1];
        for (int i = 0; i < n; i++) cum[i + 1] = cum[i] + s[i];
        double best = -1, seuil = 110, sep = 0;
        for (int k = 1; k < n; k++) {
            double m1 = cum[k] / k, m2 = (cum[n] - cum[k]) / (n - k);
            double v = (double) k * (n - k) * (m1 - m2) * (m1 - m2);
            if (v > best) {
                best = v;
                seuil = (s[k - 1] + s[k]) / 2;
                sep = m2 - m1;
            }
        }
        if (n < 2 || sep < 35) { // une seule couleur visible
            double moy = cum[n] / n;
            return moy > 110 ? -1 : 1000;
        }
        return seuil;
    }

    /** Case (0..63, ligne 0 = côté du fond) où se trouve le pied d'une pièce, ou -1 si hors de l'échiquier. */
    static int caseDe(double[] versPlateau, double x, double y) {
        double[] uv = Coins.applique(versPlateau, x, y);
        if (uv[2] <= 0) return -1;
        if (uv[0] < -0.3 || uv[0] > 8.3 || uv[1] < -0.3 || uv[1] > 8.3) return -1;
        int col = (int) Math.max(0, Math.min(7, Math.floor(uv[0])));
        int row = (int) Math.max(0, Math.min(7, Math.floor(uv[1])));
        return row * 8 + col;
    }

    /** Occupation des 64 cases : 0 vide, 1 blanc, 2 noir. */
    public static int[] occupation(double[][] coins, double[] xs, double[] ys, double[] lums) {
        int[] obs = new int[64];
        double[] h = Coins.homographie(coins, Coins.SQ);
        if (h == null) return obs;
        int n = xs.length;
        int[] idx = new int[n];
        int k = 0;
        double[] l = new double[n];
        for (int i = 0; i < n; i++) {
            int c = caseDe(h, xs[i], ys[i]);
            idx[i] = c;
            if (c >= 0) l[k++] = lums[i];
        }
        double s = seuil(Arrays.copyOf(l, k));
        for (int i = 0; i < n; i++) if (idx[i] >= 0 && obs[idx[i]] == 0) obs[idx[i]] = lums[i] > s ? 1 : 2;
        return obs;
    }

    /** Position complète (lettres FEN, majuscule = blanc) vue depuis la caméra : ligne 0 = côté du fond. */
    public static char[] lire(double[][] coins, double[] xs, double[] ys, double[] lums, int[] cls, double[] conf) {
        char[] b = new char[64];
        Arrays.fill(b, '.');
        double[] h = Coins.homographie(coins, Coins.SQ);
        if (h == null) return b;
        int n = xs.length;
        int[] idx = new int[n];
        double[] l = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            idx[i] = caseDe(h, xs[i], ys[i]);
            if (idx[i] >= 0) l[k++] = lums[i];
        }
        double s = seuil(Arrays.copyOf(l, k));
        double[] meilleur = new double[64];
        for (int i = 0; i < n; i++) {
            int c = idx[i];
            if (c < 0 || cls[i] < 0 || cls[i] > 5) continue;
            if (b[c] != '.' && conf[i] <= meilleur[c]) continue;
            char t = TYPES[cls[i]];
            b[c] = lums[i] > s ? Character.toUpperCase(t) : t;
            meilleur[c] = conf[i];
        }
        return b;
    }

    public static char[] tourne180(char[] b) {
        char[] r = new char[64];
        for (int i = 0; i < 64; i++) r[63 - i] = b[i];
        return r;
    }

    /** Positif si les blancs sont plutôt en bas (lignes 4 à 7) et les noirs en haut : orientation standard. */
    public static int scoreOrientation(char[] b) {
        int s = 0;
        for (int i = 0; i < 64; i++) {
            if (b[i] == '.') continue;
            boolean blanc = Character.isUpperCase(b[i]);
            boolean bas = i >= 32;
            s += (blanc == bas) ? 1 : -1;
        }
        return s;
    }

    /** Une position plausible : 1 roi par camp, pas de pion sur la 1re/8e rangée, pas trop de pièces. */
    public static boolean valide(char[] b) {
        int rb = 0, rn = 0, pb = 0, pn = 0, tb = 0, tn = 0;
        for (int i = 0; i < 64; i++) {
            char c = b[i];
            if (c == '.') continue;
            boolean blanc = Character.isUpperCase(c);
            if (blanc) tb++; else tn++;
            char t = Character.toLowerCase(c);
            if (t == 'k') { if (blanc) rb++; else rn++; }
            if (t == 'p') {
                if (i < 8 || i >= 56) return false;
                if (blanc) pb++; else pn++;
            }
        }
        return rb == 1 && rn == 1 && pb <= 8 && pn <= 8 && tb <= 16 && tn <= 16;
    }

    /** FEN d'une position ; trait = 'w' ou 'b'. Les roques sont autorisés quand roi et tour sont à leur place. */
    public static String fen(char[] b, char trait) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int vides = 0;
            for (int c = 0; c < 8; c++) {
                char p = b[r * 8 + c];
                if (p == '.') { vides++; continue; }
                if (vides > 0) { sb.append(vides); vides = 0; }
                sb.append(p);
            }
            if (vides > 0) sb.append(vides);
            if (r < 7) sb.append('/');
        }
        StringBuilder ro = new StringBuilder();
        if (b[60] == 'K' && b[63] == 'R') ro.append('K');
        if (b[60] == 'K' && b[56] == 'R') ro.append('Q');
        if (b[4] == 'k' && b[7] == 'r') ro.append('k');
        if (b[4] == 'k' && b[0] == 'r') ro.append('q');
        if (ro.length() == 0) ro.append('-');
        return sb + " " + trait + " " + ro + " - 0 1";
    }

    /** Écart entre l'occupation vue et la position de départ (0 = identique). */
    public static int ecartDepart(int[] obs) {
        int[] att = Partie.occupation(Partie.depuisFen(FEN_DEPART));
        return Partie.ecart(obs, att);
    }

    /** Position lue -> board standard (blancs en bas), et true si il a fallu retourner (je joue les noirs). */
    public static boolean orienteNoirs(char[] lu) {
        return scoreOrientation(tourne180(lu)) > scoreOrientation(lu);
    }

    // ------------------------------------------------------------------
    // Comparaison "souple" : une pièce posée près d'une ligne entre deux cases
    // est rattachée à la case attendue la plus proche, pas à une case choisie d'avance.
    // ------------------------------------------------------------------
    static final double RAYON = 0.7; // distance maximale (en cases) entre une pièce vue et sa case

    public static final class Vue {
        final double[] u, v;
        final int[] col; // 1 blanc, 2 noir
        final char[] typ; // b k n p q r
        final int n;
        int axe = 1;        // axe (0 = u, 1 = v) le long duquel on se rapproche de la caméra
        double signe = 1;   // sens (+1 / -1) vers la caméra le long de cet axe

        Vue(double[] u, double[] v, int[] col, char[] typ, int n) {
            this.u = u; this.v = v; this.col = col; this.typ = typ; this.n = n;
        }

        /** Une pièce attendue en s peut être cachée si une pièce vue se trouve juste devant elle (côté caméra). */
        boolean cachee(int s) {
            double cu = s % 8 + 0.5, cv = s / 8 + 0.5;
            for (int i = 0; i < n; i++) {
                double along = (axe == 0 ? u[i] - cu : v[i] - cv) * signe;
                double perp = Math.abs(axe == 0 ? v[i] - cv : u[i] - cu);
                if (along > 0.3 && along < 1.9 && perp < 0.75) return true;
            }
            return false;
        }

        public int nbPieces() { return n; }

        /** {erreurs, somme des distances} entre ce qu'on voit et une position attendue (lettres FEN). */
        double[] compare(char[] att) {
            int nAtt = 0;
            for (int s = 0; s < 64; s++) if (att[s] != '.') nAtt++;
            List<double[]> paires = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int s = 0; s < 64; s++) {
                    char a = att[s];
                    if (a == '.') continue;
                    int c = Character.isUpperCase(a) ? 1 : 2;
                    if (c != col[i]) continue;
                    double du = u[i] - (s % 8 + 0.5), dv = v[i] - (s / 8 + 0.5);
                    double d = Math.sqrt(du * du + dv * dv);
                    if (d > RAYON) continue;
                    double mal = Character.toLowerCase(a) == typ[i] ? 0 : 1;
                    paires.add(new double[]{d + 0.4 * mal, i, s, mal, d});
                }
            }
            paires.sort((x, y) -> Double.compare(x[0], y[0]));
            boolean[] pris = new boolean[n];
            boolean[] occupee = new boolean[64];
            int nb = 0;
            double somme = 0, mauvais = 0;
            for (double[] p : paires) {
                int i = (int) p[1], s = (int) p[2];
                if (pris[i] || occupee[s]) continue;
                pris[i] = true;
                occupee[s] = true;
                nb++;
                somme += p[4];
                mauvais += p[3];
            }
            double manquantes = 0;
            for (int s = 0; s < 64; s++) {
                if (att[s] == '.' || occupee[s]) continue;
                manquantes += cachee(s) ? 0.25 : 1.0; // une pièce cachée derrière une autre n'est pas une vraie erreur
            }
            return new double[]{(n - nb) + manquantes + 0.5 * mauvais, somme};
        }

        public int ecart(char[] att) { return (int) Math.round(compare(att)[0]); }
    }

    /** Pièces vues, placées en cases (coordonnées du plateau). Les doublons (même pièce vue deux fois) sont retirés. */
    public static Vue vue(double[][] coins, double[] xs, double[] ys, double[] lums, int[] cls, double[] conf) {
        double[] h = Coins.homographie(coins, Coins.SQ);
        int n = xs.length;
        double[] u = new double[n], v = new double[n], l = new double[n];
        boolean[] ok = new boolean[n];
        int k = 0;
        if (h != null) {
            for (int i = 0; i < n; i++) {
                double[] uv = Coins.applique(h, xs[i], ys[i]);
                if (uv[2] <= 0) continue;
                if (uv[0] < -0.3 || uv[0] > 8.3 || uv[1] < -0.3 || uv[1] > 8.3) continue;
                ok[i] = true;
                u[i] = uv[0];
                v[i] = uv[1];
                l[k++] = lums[i];
            }
        }
        double seuil = seuil(Arrays.copyOf(l, k));
        Integer[] ordre = new Integer[n];
        for (int i = 0; i < n; i++) ordre[i] = i;
        Arrays.sort(ordre, (a, b) -> Double.compare(conf[b], conf[a]));
        List<Integer> gardes = new ArrayList<>();
        for (int idx : ordre) {
            if (!ok[idx]) continue;
            boolean dbl = false;
            boolean cI = lums[idx] > seuil;
            for (int g : gardes) {
                boolean cG = lums[g] > seuil;
                double du = u[idx] - u[g], dv = v[idx] - v[g];
                if (cI == cG && du * du + dv * dv < 0.5 * 0.5) { dbl = true; break; }
            }
            if (!dbl) gardes.add(idx);
        }
        int m = gardes.size();
        double[] uu = new double[m], vv = new double[m];
        int[] cc = new int[m];
        char[] tt = new char[m];
        for (int j = 0; j < m; j++) {
            int g = gardes.get(j);
            uu[j] = u[g];
            vv[j] = v[g];
            cc[j] = lums[g] > seuil ? 1 : 2;
            tt[j] = (cls[g] >= 0 && cls[g] <= 5) ? TYPES[cls[g]] : '?';
        }
        Vue res = new Vue(uu, vv, cc, tt, m);
        double[] hi = Coins.homographie(Coins.SQ, coins);
        if (hi != null) {
            double y0 = Coins.applique(hi, 4, 4)[1];
            double dU = Coins.applique(hi, 5, 4)[1] - y0;
            double dV = Coins.applique(hi, 4, 5)[1] - y0;
            if (Math.abs(dU) > Math.abs(dV)) { res.axe = 0; res.signe = dU >= 0 ? 1 : -1; }
            else { res.axe = 1; res.signe = dV >= 0 ? 1 : -1; }
        }
        return res;
    }

    /** Coups légaux classés du plus au moins probable d'après ce qu'on voit (comparaison souple). */
    public static List<Partie.Note> classeSouple(Vue vue, char[] b, List<String> legaux) {
        List<double[]> cout = new ArrayList<>();
        List<String> coups = new ArrayList<>();
        for (String m : legaux) {
            cout.add(vue.compare(Partie.apres(b, m)));
            coups.add(m);
        }
        Integer[] ordre = new Integer[coups.size()];
        for (int i = 0; i < ordre.length; i++) ordre[i] = i;
        Arrays.sort(ordre, (x, y) -> {
            int c = Double.compare(cout.get(x)[0], cout.get(y)[0]);
            return c != 0 ? c : Double.compare(cout.get(x)[1], cout.get(y)[1]);
        });
        List<Partie.Note> r = new ArrayList<>();
        for (int i : ordre) r.add(new Partie.Note((int) Math.round(cout.get(i)[0]), coups.get(i), cout.get(i)[0] + cout.get(i)[1]));
        return r;
    }
}
