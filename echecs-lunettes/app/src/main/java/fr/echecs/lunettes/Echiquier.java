package fr.echecs.lunettes;

import java.util.ArrayList;
import java.util.List;

/**
 * Position d'échecs avec génération des coups légaux (sans Stockfish : instantané, sert au suivi de partie).
 * Cases : index = rangée * 8 + colonne, rangée 0 = 8e rangée, colonne 0 = colonne a.
 * Promotion : toujours en dame (comme Moteur.coupsLegaux).
 */
public final class Echiquier {
    public final char[] b;
    public final boolean blancsJouent;
    final int roques; // bits : 1 = petit blanc, 2 = grand blanc, 4 = petit noir, 8 = grand noir
    final int passant; // case "en passant" ou -1

    private Echiquier(char[] b, boolean blancs, int roques, int passant) {
        this.b = b; this.blancsJouent = blancs; this.roques = roques; this.passant = passant;
    }

    public static Echiquier depart() { return depuisFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"); }

    public static Echiquier depuisFen(String fen) {
        String[] t = fen.trim().split(" ");
        char[] b = Partie.depuisFen(fen);
        boolean w = t.length < 2 || t[1].equals("w");
        int r = 0;
        if (t.length > 2) {
            if (t[2].contains("K")) r |= 1;
            if (t[2].contains("Q")) r |= 2;
            if (t[2].contains("k")) r |= 4;
            if (t[2].contains("q")) r |= 8;
        }
        int ep = -1;
        if (t.length > 3 && t[3].length() == 2) ep = Partie.caseDe(t[3]);
        return new Echiquier(b, w, r, ep);
    }

    public String cle() { return new String(b) + (blancsJouent ? 'w' : 'b') + roques + passant; }

    private static boolean blanche(char p) { return Character.isUpperCase(p); }

    private static final int[][] CAV = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
    private static final int[][] DIAG = {{-1,-1},{-1,1},{1,-1},{1,1}};
    private static final int[][] DROIT = {{-1,0},{1,0},{0,-1},{0,1}};

    /** La case s est-elle attaquée par le camp "parBlancs" ? */
    boolean attaquee(int s, boolean parBlancs) {
        int r = s / 8, c = s % 8;
        // pions : un pion blanc attaque vers le haut (rangée qui diminue)
        int dr = parBlancs ? 1 : -1; // le pion attaquant est sur la rangée r+dr
        char pion = parBlancs ? 'P' : 'p';
        for (int dc = -1; dc <= 1; dc += 2) {
            int rr = r + dr, cc = c + dc;
            if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && b[rr * 8 + cc] == pion) return true;
        }
        char cav = parBlancs ? 'N' : 'n';
        for (int[] d : CAV) {
            int rr = r + d[0], cc = c + d[1];
            if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && b[rr * 8 + cc] == cav) return true;
        }
        char roi = parBlancs ? 'K' : 'k';
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            if (i == 0 && j == 0) continue;
            int rr = r + i, cc = c + j;
            if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && b[rr * 8 + cc] == roi) return true;
        }
        char fou = parBlancs ? 'B' : 'b', tour = parBlancs ? 'R' : 'r', dame = parBlancs ? 'Q' : 'q';
        for (int[] d : DIAG) {
            int rr = r + d[0], cc = c + d[1];
            while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                char p = b[rr * 8 + cc];
                if (p != '.') { if (p == fou || p == dame) return true; break; }
                rr += d[0]; cc += d[1];
            }
        }
        for (int[] d : DROIT) {
            int rr = r + d[0], cc = c + d[1];
            while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                char p = b[rr * 8 + cc];
                if (p != '.') { if (p == tour || p == dame) return true; break; }
                rr += d[0]; cc += d[1];
            }
        }
        return false;
    }

    private int roi(boolean blanc) {
        char k = blanc ? 'K' : 'k';
        for (int i = 0; i < 64; i++) if (b[i] == k) return i;
        return -1;
    }

    public boolean enEchec() {
        int k = roi(blancsJouent);
        return k >= 0 && attaquee(k, !blancsJouent);
    }

    private void ajoute(List<String> l, int de, int vers, char promo) {
        String s = Partie.nomCase(de) + Partie.nomCase(vers);
        l.add(promo == 0 ? s : s + promo);
    }

    private void pseudo(List<String> l) {
        boolean w = blancsJouent;
        for (int s = 0; s < 64; s++) {
            char p = b[s];
            if (p == '.' || blanche(p) != w) continue;
            int r = s / 8, c = s % 8;
            char t = Character.toLowerCase(p);
            if (t == 'p') {
                int dir = w ? -1 : 1, dep = w ? 6 : 1, fin = w ? 0 : 7;
                int r1 = r + dir;
                if (r1 < 0 || r1 > 7) continue;
                if (b[r1 * 8 + c] == '.') {
                    ajoute(l, s, r1 * 8 + c, r1 == fin ? 'q' : (char) 0);
                    if (r == dep && b[(r + 2 * dir) * 8 + c] == '.') ajoute(l, s, (r + 2 * dir) * 8 + c, (char) 0);
                }
                for (int dc = -1; dc <= 1; dc += 2) {
                    int cc = c + dc;
                    if (cc < 0 || cc > 7) continue;
                    int v = r1 * 8 + cc;
                    char q = b[v];
                    if (q != '.' && blanche(q) != w) ajoute(l, s, v, r1 == fin ? 'q' : (char) 0);
                    else if (q == '.' && v == passant) ajoute(l, s, v, (char) 0);
                }
            } else if (t == 'n') {
                for (int[] d : CAV) {
                    int rr = r + d[0], cc = c + d[1];
                    if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
                    char q = b[rr * 8 + cc];
                    if (q == '.' || blanche(q) != w) ajoute(l, s, rr * 8 + cc, (char) 0);
                }
            } else if (t == 'k') {
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
                    if (i == 0 && j == 0) continue;
                    int rr = r + i, cc = c + j;
                    if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
                    char q = b[rr * 8 + cc];
                    if (q == '.' || blanche(q) != w) ajoute(l, s, rr * 8 + cc, (char) 0);
                }
                int base = w ? 60 : 4;
                if (s == base && !attaquee(s, !w)) {
                    int petit = w ? 1 : 4, grand = w ? 2 : 8;
                    char tour = w ? 'R' : 'r';
                    if ((roques & petit) != 0 && b[base + 3] == tour && b[base + 1] == '.' && b[base + 2] == '.'
                            && !attaquee(base + 1, !w) && !attaquee(base + 2, !w)) ajoute(l, s, base + 2, (char) 0);
                    if ((roques & grand) != 0 && b[base - 4] == tour && b[base - 1] == '.' && b[base - 2] == '.' && b[base - 3] == '.'
                            && !attaquee(base - 1, !w) && !attaquee(base - 2, !w)) ajoute(l, s, base - 2, (char) 0);
                }
            } else {
                int[][] dirs = t == 'b' ? DIAG : t == 'r' ? DROIT : null;
                for (int k = 0; k < (t == 'q' ? 2 : 1); k++) {
                    int[][] dd = t == 'q' ? (k == 0 ? DIAG : DROIT) : dirs;
                    for (int[] d : dd) {
                        int rr = r + d[0], cc = c + d[1];
                        while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                            char q = b[rr * 8 + cc];
                            if (q == '.') ajoute(l, s, rr * 8 + cc, (char) 0);
                            else { if (blanche(q) != w) ajoute(l, s, rr * 8 + cc, (char) 0); break; }
                            rr += d[0]; cc += d[1];
                        }
                    }
                }
            }
        }
    }

    /** Coups légaux (UCI), promotion en dame seulement. */
    public List<String> coups() {
        List<String> ps = new ArrayList<>();
        pseudo(ps);
        List<String> r = new ArrayList<>();
        for (String m : ps) {
            Echiquier n = jouer(m);
            int k = n.roi(blancsJouent);
            if (k >= 0 && !n.attaquee(k, !blancsJouent)) r.add(m);
        }
        return r;
    }

    public Echiquier jouer(String uci) {
        char[] n = Partie.apres(b, uci);
        int de = Partie.caseDe(uci.substring(0, 2)), vers = Partie.caseDe(uci.substring(2, 4));
        char p = b[de];
        int ro = roques;
        if (p == 'K') ro &= ~3;
        if (p == 'k') ro &= ~12;
        if (de == 63 || vers == 63) ro &= ~1;
        if (de == 56 || vers == 56) ro &= ~2;
        if (de == 7 || vers == 7) ro &= ~4;
        if (de == 0 || vers == 0) ro &= ~8;
        int ep = -1;
        if (Character.toLowerCase(p) == 'p' && Math.abs(vers / 8 - de / 8) == 2) ep = (de + vers) / 2;
        return new Echiquier(n, !blancsJouent, ro, ep);
    }

    public String fen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int v = 0;
            for (int c = 0; c < 8; c++) {
                char p = b[r * 8 + c];
                if (p == '.') { v++; continue; }
                if (v > 0) { sb.append(v); v = 0; }
                sb.append(p);
            }
            if (v > 0) sb.append(v);
            if (r < 7) sb.append('/');
        }
        String ro = ((roques & 1) != 0 ? "K" : "") + ((roques & 2) != 0 ? "Q" : "") + ((roques & 4) != 0 ? "k" : "") + ((roques & 8) != 0 ? "q" : "");
        return sb + " " + (blancsJouent ? "w" : "b") + " " + (ro.isEmpty() ? "-" : ro) + " " + (passant >= 0 ? Partie.nomCase(passant) : "-") + " 0 1";
    }

    // test
    public static long perft(Echiquier e, int d) {
        if (d == 0) return 1;
        long n = 0;
        for (String m : e.coups()) n += perft(e.jouer(m), d - 1);
        return n;
    }
}
