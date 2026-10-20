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
        /** Même chose sans arrondi, distances comprises : sert à départager deux coups proches. */
        public final double fin;
        Note(int e, String c) { ecart = e; coup = c; fin = e; }
        Note(int e, String c, double f) { ecart = e; coup = c; fin = f; }
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

    /** Comme marge, mais sans arrondi (écart fin entre le meilleur coup et le meilleur coup différent). */
    public static double margeFine(List<Note> notes) {
        if (notes.isEmpty()) return 0;
        String a = notes.get(0).coup.substring(0, 4);
        for (int i = 1; i < notes.size(); i++)
            if (!notes.get(i).coup.substring(0, 4).equals(a)) return notes.get(i).fin - notes.get(0).fin;
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

    static String article(char p) {
        switch (Character.toLowerCase(p)) {
            case 'p': return "le pion";
            case 'n': return "le cavalier";
            case 'b': return "le fou";
            case 'r': return "la tour";
            case 'q': return "la dame";
            case 'k': return "le roi";
            default: return "la pièce";
        }
    }

    static String indefini(char p) {
        switch (Character.toLowerCase(p)) {
            case 'n': return "un cavalier";
            case 'b': return "un fou";
            case 'r': return "une tour";
            default: return "une dame";
        }
    }

    static String maj(String t) {
        return t.substring(0, 1).toUpperCase() + t.substring(1);
    }

    /** Façon de prononcer les cases (0 à 3), au choix de l'utilisateur. */
    public static int style = 0;

    /** Case prononcée clairement, selon le style choisi. */
    static String caseParlee(int idx) {
        String[] chiffres = {"un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit"};
        int col = idx % 8, rang = 7 - idx / 8;
        String maj = "" + (char) ('A' + col);
        switch (style) {
            case 1: return maj + " " + chiffres[rang];
            case 2: return "colonne " + maj + ", ligne " + chiffres[rang];
            case 3: return "" + (char) ('a' + col) + (rang + 1);
            default:
                String[] lettres = {"a", "bé", "cé", "dé", "euh", "effe", "gé", "hache"};
                return lettres[col] + " " + chiffres[rang];
        }
    }

    /** Phrase pour l'écran : « Le fou en c1, prend le pion en g5. » */
    public static String decrire(char[] b, String uci) {
        return phrase(b, uci, false);
    }

    /** Même phrase, écrite pour être prononcée (cases épelées, pauses). */
    public static String dire(char[] b, String uci) {
        return phrase(b, uci, true);
    }

    private static String phrase(char[] b, String uci, boolean parle) {
        int de = caseDe(uci.substring(0, 2)), vers = caseDe(uci.substring(2, 4));
        char p = b[de];
        if (Character.toLowerCase(p) == 'k' && Math.abs(vers % 8 - de % 8) == 2) {
            boolean petit = vers % 8 > de % 8;
            int rang = de / 8;
            int roiVers = vers;
            int tourVers = rang * 8 + (petit ? 5 : 3);
            String c1 = parle ? caseParlee(roiVers) : nomCase(roiVers);
            String c2 = parle ? caseParlee(tourVers) : nomCase(tourVers);
            return (petit ? "Petit roque" : "Grand roque") + ", le roi va en " + c1 + " et la tour en " + c2;
        }
        boolean enPassant = Character.toLowerCase(p) == 'p' && de % 8 != vers % 8 && b[vers] == '.';
        boolean prise = b[vers] != '.' || enPassant;
        String cd = parle ? caseParlee(de) : nomCase(de);
        String cv = parle ? caseParlee(vers) : nomCase(vers);
        String t = maj(article(p)) + " en " + cd + ", ";
        if (prise) {
            String cible = enPassant ? "le pion" : article(b[vers]);
            t += "prend " + cible + " en " + (enPassant ? cv + ", en passant" : cv);
        } else {
            t += "va en " + cv;
        }
        if (uci.length() >= 5) t += ", et devient " + indefini(uci.charAt(4));
        return t;
    }
}
