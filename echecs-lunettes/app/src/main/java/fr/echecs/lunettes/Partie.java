package fr.echecs.lunettes;

import java.util.ArrayList;
import java.util.List;

/**
 * Logique de comparaison entre ce que voit la caméra et les coups légaux.
 * Cases : index = rangée * 8 + colonne, rangée 0 = 8e rangée (côté noir), colonne 0 = colonne a.
 * Occupation : 0 = vide, 1 = pièce blanche (claire), 2 = pièce noire (foncée).
 */
public final class Partie {

    /** Plateau depuis la partie "pièces" d'une FEN. '.' = case vide. */
    public static char[] depuisFen(String fen) {
        char[] b = new char[64];
        java.util.Arrays.fill(b, '.');
        String place = fen.trim().split(" ")[0];
        int r = 0, c = 0;
        for (char ch : place.toCharArray()) {
            if (ch == '/') { r++; c = 0; }
            else if (Character.isDigit(ch)) c += ch - '0';
            else { if (r < 8 && c < 8) b[r * 8 + c] = ch; c++; }
        }
        return b;
    }

    static int caseDe(String sq) {
        int col = sq.charAt(0) - 'a';
        int rang = sq.charAt(1) - '0';
        return (8 - rang) * 8 + col;
    }

    public static String nomCase(int idx) {
        return "" + (char) ('a' + idx % 8) + (8 - idx / 8);
    }

    /** Position après un coup (format UCI, ex. e2e4, e7e8q). Gère prise en passant, roque, promotion. */
    public static char[] apres(char[] b, String uci) {
        char[] n = b.clone();
        int de = caseDe(uci.substring(0, 2)), vers = caseDe(uci.substring(2, 4));
        char p = b[de];
        n[de] = '.';
        boolean pion = Character.toLowerCase(p) == 'p';
        if (pion && de % 8 != vers % 8 && b[vers] == '.') {
            n[(de / 8) * 8 + vers % 8] = '.'; // en passant
        }
        if (Character.toLowerCase(p) == 'k' && Math.abs(vers % 8 - de % 8) == 2) {
            int rang = de / 8;
            boolean petit = vers % 8 > de % 8;
            int tourDe = rang * 8 + (petit ? 7 : 0), tourVers = rang * 8 + (petit ? 5 : 3);
            n[tourVers] = n[tourDe];
            n[tourDe] = '.';
        }
        char np = p;
        if (uci.length() >= 5) {
            char pr = uci.charAt(4);
            np = Character.isUpperCase(p) ? Character.toUpperCase(pr) : Character.toLowerCase(pr);
        }
        n[vers] = np;
        return n;
    }

    public static int[] occupation(char[] b) {
        int[] o = new int[64];
        for (int i = 0; i < 64; i++) o[i] = b[i] == '.' ? 0 : (Character.isUpperCase(b[i]) ? 1 : 2);
        return o;
    }

    public static int ecart(int[] obs, int[] attendu) {
        int n = 0;
        for (int i = 0; i < 64; i++) if (obs[i] != attendu[i]) n++;
        return n;
    }

    public static final class Note {
        public final int ecart;
        public final String coup;
        Note(int e, String c) { ecart = e; coup = c; }
    }

    /** Coups légaux classés du plus probable au moins probable. */
    public static List<Note> classe(char[] b, List<String> legaux, int[] obs) {
        List<Note> r = new ArrayList<>();
        for (String m : legaux) r.add(new Note(ecart(obs, occupation(apres(b, m))), m));
        r.sort((x, y) -> Integer.compare(x.ecart, y.ecart));
        return r;
    }

    /** Écart entre le 1er coup et le 1er coup *différent* (même départ/arrivée = même coup, ex. promotions). */
    public static int marge(List<Note> notes) {
        if (notes.isEmpty()) return 0;
        String a = notes.get(0).coup.substring(0, 4);
        for (int i = 1; i < notes.size(); i++)
            if (!notes.get(i).coup.substring(0, 4).equals(a)) return notes.get(i).ecart - notes.get(0).ecart;
        return 99;
    }

    static String nomPiece(char p) {
        switch (Character.toLowerCase(p)) {
            case 'p': return "Pion";
            case 'n': return "Cavalier";
            case 'b': return "Fou";
            case 'r': return "Tour";
            case 'q': return "Dame";
            case 'k': return "Roi";
            default: return "Pièce";
        }
    }

    /** Phrase claire : « Fou de c1 prend en g5 ». */
    public static String decrire(char[] b, String uci) {
        int de = caseDe(uci.substring(0, 2)), vers = caseDe(uci.substring(2, 4));
        char p = b[de];
        if (Character.toLowerCase(p) == 'k' && Math.abs(vers % 8 - de % 8) == 2)
            return vers % 8 > de % 8 ? "Petit roque" : "Grand roque";
        boolean prise = b[vers] != '.' || (Character.toLowerCase(p) == 'p' && de % 8 != vers % 8);
        String t = nomPiece(p) + " de " + nomCase(de) + (prise ? " prend en " : " en ") + nomCase(vers);
        if (uci.length() >= 5) t += ", promotion en " + nomPiece(uci.charAt(4)).toLowerCase();
        return t;
    }
}
