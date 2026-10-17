package com.despejo.gravador;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A árvore de um mapa mental: tronco, galhos e folhas, mais ligações entre ideias.
 * Vem do JSON que o Claude agendado escreve; se não tiver JSON, monta a partir do texto em markdown.
 */
final class MindMap {

    static final class Node {
        String id;
        String label;
        String icon = "";
        String type = "";
        String note = "";
        final List<Node> kids = new ArrayList<>();
        Node parent;
        boolean collapsed;

        // Preenchidos pela tela na hora de desenhar.
        int depth;
        int side = 1;
        int color;
        float x, y, w, h;
        android.text.StaticLayout text;

        Node(String label) {
            this.label = label;
        }

        Node add(Node k) {
            k.parent = this;
            kids.add(k);
            return k;
        }

        int countAll() {
            int n = kids.size();
            for (Node k : kids) n += k.countAll();
            return n;
        }
    }

    static final class Link {
        final String from, to, label;

        Link(String from, String to, String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }

    Node root;
    final List<Link> links = new ArrayList<>();
    final Map<String, Node> byId = new HashMap<>();

    /** Ícone padrão de cada tipo de folha, para dar para entender sem ler. */
    static String iconFor(String type) {
        switch (type) {
            case "projeto": return "📁";
            case "ideia": return "💡";
            case "tarefa": return "✅";
            case "problema": return "⚠️";
            case "objetivo": return "🎯";
            case "pergunta": return "❓";
            case "pessoa": return "👤";
            default: return "";
        }
    }

    static MindMap fromJson(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        MindMap m = new MindMap();
        m.root = m.readNode(o);
        JSONArray ls = o.optJSONArray("ligacoes");
        if (ls != null) {
            for (int i = 0; i < ls.length(); i++) {
                JSONObject l = ls.getJSONObject(i);
                m.links.add(new Link(l.optString("de"), l.optString("para"), l.optString("rotulo")));
            }
        }
        return m;
    }

    private Node readNode(JSONObject o) {
        Node n = new Node(o.optString("titulo", "?"));
        n.id = o.optString("id", null);
        n.type = o.optString("tipo");
        n.icon = o.optString("icone", iconFor(n.type));
        n.note = o.optString("nota");
        if (n.id != null) byId.put(n.id, n);
        JSONArray ks = o.optJSONArray("filhos");
        if (ks != null) {
            for (int i = 0; i < ks.length(); i++) {
                JSONObject k = ks.optJSONObject(i);
                if (k != null) n.add(readNode(k));
            }
        }
        return n;
    }

    /**
     * Monta a árvore a partir do markdown: cada "## Seção" vira um galho e as listas viram folhas.
     * A seção "Mapa" vai direto no tronco; "Em uma frase" e "Ligações" ficam de fora.
     */
    static MindMap fromMarkdown(String md, String fallbackTitle) {
        MindMap m = new MindMap();
        m.root = new Node(fallbackTitle);
        Node section = m.root;
        boolean skip = false;
        List<Node> stack = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String raw : md.split("\n")) {
            String line = raw.replace("\t", "    ");
            String t = line.trim();
            if (t.startsWith("# ")) {
                m.root.label = clean(t.substring(2));
                continue;
            }
            if (t.startsWith("## ")) {
                String h = clean(t.substring(3));
                String low = h.toLowerCase();
                skip = low.startsWith("em uma frase") || low.startsWith("ligações") || low.startsWith("ligacoes");
                if (low.equals("mapa")) {
                    section = m.root;
                } else {
                    section = new Node(h);
                    if (low.startsWith("pendências") || low.startsWith("pendencias")) section.icon = "✅";
                    else if (low.startsWith("projetos")) section.icon = "📁";
                    else if (low.startsWith("viabilidade")) section.icon = "🔍";
                    if (!skip) m.root.add(section);
                }
                stack.clear();
                indents.clear();
                continue;
            }
            if (skip) continue;
            if (!(t.startsWith("- ") || t.startsWith("* "))) continue;
            int indent = line.indexOf(t.charAt(0));
            String label = t.substring(2);
            boolean task = false;
            if (label.startsWith("[ ] ") || label.startsWith("[x] ")) { task = true; label = label.substring(4); }
            Node n = new Node(clean(label));
            if (task) n.icon = "✅";
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                stack.remove(stack.size() - 1);
            }
            Node parent = stack.isEmpty() ? section : stack.get(stack.size() - 1);
            parent.add(n);
            stack.add(n);
            indents.add(indent);
        }
        // Um único galho com tudo dentro vira o próprio tronco.
        if (m.root.kids.size() == 1 && m.root.kids.get(0).kids.size() > 1 && md.contains("## Mapa")) {
            Node only = m.root.kids.get(0);
            m.root.label = only.label;
            m.root.kids.clear();
            for (Node k : only.kids) m.root.add(k);
        }
        return m;
    }

    /** Tira negrito, links e "(origem)" e encurta frases longas (o texto inteiro fica como nota). */
    private static String clean(String s) {
        s = s.replaceAll("\\(\\[origem\\]\\([^)]*\\)\\)", "")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replace("**", "").replace("`", "").trim();
        return s;
    }

    private MindMap() { }
}
