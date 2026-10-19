package fr.echecs.lunettes;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/** Dialogue avec Stockfish (protocole UCI). */
public final class Moteur {

    private final Process proc;
    private final BufferedWriter out;
    private final BufferedReader in;

    public Moteur(String chemin) throws IOException {
        proc = new ProcessBuilder(chemin).redirectErrorStream(true).start();
        out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
        in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        envoie("uci");
        lisJusqua("uciok");
        envoie("isready");
        lisJusqua("readyok");
    }

    private void envoie(String s) throws IOException {
        out.write(s);
        out.write("\n");
        out.flush();
    }

    private List<String> lisJusqua(String debut) throws IOException {
        List<String> lignes = new ArrayList<>();
        while (true) {
            String l = in.readLine();
            if (l == null) throw new IOException("Stockfish s'est arrêté");
            lignes.add(l);
            if (l.trim().startsWith(debut)) return lignes;
        }
    }

    /** Liste des coups légaux (format UCI) pour une commande "position ...". */
    public synchronized List<String> coupsLegaux(String position) throws IOException {
        envoie(position);
        envoie("go perft 1");
        List<String> r = new ArrayList<>();
        for (String l : lisJusqua("Nodes searched")) {
            String t = l.trim();
            int i = t.indexOf(':');
            if (i < 4 || i > 5) continue;
            String m = t.substring(0, i);
            if (m.matches("[a-h][1-8][a-h][1-8][qrbn]?")) r.add(m);
        }
        return r;
    }

    /** {fen, "1" si le camp au trait est en échec sinon "0"}. */
    public synchronized String[] etat(String position) throws IOException {
        envoie(position);
        envoie("d");
        String fen = null;
        boolean echec = false;
        for (String l : lisJusqua("Checkers")) {
            String t = l.trim();
            if (t.startsWith("Fen:")) fen = t.substring(4).trim();
            if (t.startsWith("Checkers")) {
                int i = t.indexOf(':');
                echec = i >= 0 && !t.substring(i + 1).trim().isEmpty();
            }
        }
        if (fen == null) throw new IOException("FEN introuvable");
        return new String[]{fen, echec ? "1" : "0"};
    }

    /** Meilleur coup (UCI) ou null s'il n'y en a pas. */
    public synchronized String meilleurCoup(String position, int ms) throws IOException {
        envoie(position);
        envoie("go movetime " + ms);
        List<String> l = lisJusqua("bestmove");
        String[] t = l.get(l.size() - 1).trim().split("\\s+");
        if (t.length < 2 || t[1].startsWith("(")) return null;
        return t[1];
    }

    public void ferme() {
        try { envoie("quit"); } catch (IOException ignored) { }
        proc.destroy();
    }
}
