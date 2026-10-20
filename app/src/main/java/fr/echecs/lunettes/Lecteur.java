package fr.echecs.lunettes;

import java.util.Arrays;

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
}
