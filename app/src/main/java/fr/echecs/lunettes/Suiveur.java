package fr.echecs.lunettes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Suivi de la partie par hypothèses : on garde plusieurs historiques de coups possibles, chacun avec un score
 * (erreurs cumulées entre ce que la caméra a vu et la position de l'historique, + un coût par coup).
 * Un coup raté ou une main devant l'échiquier n'abîme plus rien : l'historique le plus cohérent avec toutes les images gagne.
 */
public final class Suiveur {
    public static final class Noeud {
        public final Echiquier e;
        public final Noeud parent;
        public final String coup;
        public final int prof;
        public double score;
        final String cle;

        Noeud(Echiquier e, Noeud parent, String coup, double score) {
            this.e = e; this.parent = parent; this.coup = coup; this.score = score;
            this.prof = parent == null ? 0 : parent.prof + 1;
            this.cle = e.cle();
        }

        public List<String> historique() {
            ArrayList<String> l = new ArrayList<>();
            for (Noeud n = this; n.parent != null; n = n.parent) l.add(n.coup);
            Collections.reverse(l);
            return l;
        }
    }

    public double coutCoup = 2.0;
    public double coutDeux = 4.6;
    public int largeur = 40;
    public double fenetreDecision = 3.0;

    private Map<String, Noeud> faisceau = new HashMap<>();
    private final List<String> confirmes = new ArrayList<>();
    public int observations = 0;
    public int stabilite = 5; // observations pendant lesquelles le meilleur historique doit rester le même
    private List<String> precedent = new ArrayList<>();
    private final List<Integer> age = new ArrayList<>();

    public Suiveur(Echiquier depart) {
        Noeud r = new Noeud(depart, null, null, 0);
        faisceau.put(r.cle, r);
    }

    public List<String> confirmes() { return confirmes; }

    public Noeud meilleur() {
        Noeud b = null;
        for (Noeud n : faisceau.values()) if (b == null || n.score < b.score || (n.score == b.score && n.prof < b.prof)) b = n;
        return b;
    }

    /** Une nouvelle image : met à jour les hypothèses. Renvoie les coups nouvellement confirmés. */
    public List<String> observe(Lecteur.Vue vue) {
        observations++;
        Map<String, Noeud> cand = new HashMap<>();
        // 1) chaque hypothèse reste telle quelle
        for (Noeud n : faisceau.values()) cand.put(n.cle, n);
        Noeud top = meilleur();
        // 2) ou bien elle avance d'un coup (ou de deux pour la meilleure)
        List<Noeud> base = new ArrayList<>(faisceau.values());
        for (Noeud n : base) {
            for (String m : n.e.coups()) {
                Noeud f = new Noeud(n.e.jouer(m), n, m, n.score + coutCoup);
                Noeud ex = cand.get(f.cle);
                if (ex == null || f.score < ex.score) cand.put(f.cle, f);
                if (n == top) {
                    for (String m2 : f.e.coups()) {
                        Noeud g = new Noeud(f.e.jouer(m2), f, m2, n.score + coutDeux);
                        Noeud ex2 = cand.get(g.cle);
                        if (ex2 == null || g.score < ex2.score) cand.put(g.cle, g);
                    }
                }
            }
        }
        // 3) on ajoute ce qu'on a vu
        List<Noeud> liste = new ArrayList<>(cand.values());
        double mini = Double.MAX_VALUE;
        for (Noeud n : liste) {
            n.score += vue.compare(n.e.b)[0];
            if (n.score < mini) mini = n.score;
        }
        Collections.sort(liste, (x, y) -> Double.compare(x.score, y.score));
        faisceau = new HashMap<>();
        for (int i = 0; i < liste.size() && i < largeur; i++) {
            Noeud n = liste.get(i);
            n.score -= mini;
            faisceau.put(n.cle, n);
        }
        return decide();
    }

    /** Coups sur lesquels toutes les bonnes hypothèses sont d'accord (et qui ne reviennent pas en arrière). */
    private List<String> decide() {
        Noeud best = meilleur();
        List<String> h = best.historique();
        int commun = h.size();
        // âge de chaque coup du meilleur historique (combien d'observations de suite il y est)
        int comm = 0;
        while (comm < precedent.size() && comm < h.size() && precedent.get(comm).equals(h.get(comm))) comm++;
        while (age.size() > comm) age.remove(age.size() - 1);
        for (int i = 0; i < age.size(); i++) age.set(i, age.get(i) + 1);
        while (age.size() < h.size()) age.add(1);
        precedent = h;
        int stable = 0;
        while (stable < h.size() && age.get(stable) >= stabilite) stable++;
        for (Noeud n : faisceau.values()) {
            if (n.score > best.score + fenetreDecision) continue;
            List<String> hn = n.historique();
            int k = 0;
            while (k < commun && k < hn.size() && h.get(k).equals(hn.get(k))) k++;
            commun = Math.min(commun, k);
        }
        commun = Math.max(commun, stable);
        List<String> nouveaux = new ArrayList<>();
        // on n'avance que : jamais de retour en arrière (si l'historique confirmé diverge, on le dit)
        int k = 0;
        while (k < confirmes.size() && k < commun && confirmes.get(k).equals(h.get(k))) k++;
        if (k < confirmes.size()) {
            // désaccord avec ce qui était confirmé : on garde le confirmé tant que la nouvelle version n'est pas nette
            if (commun > k) {
                while (confirmes.size() > k) confirmes.remove(confirmes.size() - 1);
            } else return nouveaux;
        }
        for (int i = confirmes.size(); i < commun; i++) {
            confirmes.add(h.get(i));
            nouveaux.add(h.get(i));
        }
        return nouveaux;
    }
}
