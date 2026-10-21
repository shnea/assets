package kr.shnea.assets;

import java.net.URI;
import java.util.*;

/** Accept the platform document contract and keep legacy plain text readable. */
final class RichText {
    private static final Set<String> NODES=Set.of("doc","paragraph","text","heading","hardBreak","blockquote","bulletList","orderedList","listItem","codeBlock","horizontalRule","taskList","taskItem","table","tableRow","tableCell","tableHeader","externalImage","attachment","mediaRow");
    private static final Set<String> MARKS=Set.of("bold","italic","strike","underline","code","link");
    private static final Set<String> NODE_KEYS=Set.of("type","attrs","content","marks","text");
    private RichText() {}
    static Object normalize(Object value,int plainLimit) {
        if(value==null) return "";
        if(value instanceof String text) return Content.text(text,plainLimit);
        Map<?,?> root=object(value);
        if(!root.keySet().equals(Set.of("format","version","content")) || !"shnea-editor".equals(root.get("format")) || !(root.get("version") instanceof Number number) || number.doubleValue()!=3) throw Failure.invalid();
        var state=new Validation();state.node(root.get("content"),0);
        if(!"doc".equals(object(root.get("content")).get("type"))) throw Failure.invalid();
        return value;
    }
    static Map<?,?> object(Object value) {if(!(value instanceof Map<?,?> map)) throw Failure.invalid();return map;}
    private static final class Validation {
        int nodes,characters;
        final Set<String> blocks=new HashSet<>();
        void string(Object value,int limit) {
            if(!(value instanceof String text) || text.length()>limit || text.indexOf('\0')>=0) throw Failure.invalid();
            characters+=text.length();if(characters>2_000_000) throw Failure.invalid();
        }
        void node(Object value,int depth) {
            if(depth>64 || ++nodes>20_000) throw Failure.invalid();
            var node=object(value);if(!NODE_KEYS.containsAll(node.keySet()) || !(node.get("type") instanceof String type) || !NODES.contains(type)) throw Failure.invalid();
            if(node.containsKey("text")) {if(!type.equals("text")) throw Failure.invalid();string(node.get("text"),2_000_000);}
            var attrs=node.get("attrs")==null?Map.of():object(node.get("attrs"));
            for(var entry:attrs.entrySet()) {
                Object item=entry.getValue();
                if(item instanceof String) string(item,10000);
                else if(item!=null && !(item instanceof Number) && !(item instanceof Boolean) && !("colwidth".equals(entry.getKey()) && item instanceof List<?> widths && widths.size()<=100 && widths.stream().allMatch(n->n instanceof Number))) throw Failure.invalid();
            }
            if(type.equals("attachment")) {
                for(String key:List.of("id","fileId","scope","name","kind")) string(attrs.get(key),1000);
                if(!Set.of("file","image","video","audio").contains(attrs.get("kind")) || !(attrs.get("size") instanceof Number size) || size.longValue()<0 || size.longValue()>5_000_000_000L) throw Failure.invalid();
                uuid(attrs.get("id"));if(!blocks.add((String)attrs.get("id"))) throw Failure.invalid();
                if(!((String)attrs.get("fileId")).isBlank()) uuid(attrs.get("fileId"));
                if(((String)attrs.get("scope")).isBlank() || ((String)attrs.get("name")).isBlank()) throw Failure.invalid();
            }
            if(type.equals("mediaRow")) {uuid(attrs.get("id"));if(!blocks.add((String)attrs.get("id"))) throw Failure.invalid();}
            if(type.equals("externalImage") && attrs.get("source") instanceof String source && source.matches("(?is)^\\s*(data|blob|javascript):.*")) throw Failure.invalid();
            if(node.containsKey("marks")) {
                if(!(node.get("marks") instanceof List<?> marks) || marks.size()>8) throw Failure.invalid();
                for(Object item:marks) {
                    var mark=object(item);if(!Set.of("type","attrs").containsAll(mark.keySet()) || !(mark.get("type") instanceof String markType) || !MARKS.contains(markType)) throw Failure.invalid();
                    if("link".equals(mark.get("type"))) {
                        Object href=object(mark.get("attrs")).get("href");string(href,10000);
                        try {String link=(String)href;var uri=URI.create(link);if(!Set.of("http","https","mailto","tel").contains(uri.getScheme()) || link.matches("(?s).*[\\s\\p{Cntrl}].*")) throw Failure.invalid();}
                        catch(IllegalArgumentException e) {throw Failure.invalid();}
                    }
                }
            }
            if(node.containsKey("content")) {
                if(!(node.get("content") instanceof List<?> children)) throw Failure.invalid();
                for(Object child:children) node(child,depth+1);
            }
        }
    }
    static UUID uuid(Object value) {try{return UUID.fromString((String)value);}catch(Exception e){throw Failure.invalid();}}
    static String plain(Object value) {
        if(value instanceof String text) return text;
        if(!(value instanceof Map<?,?> root)) return "";
        var result=new StringBuilder();plainNode(root.get("content"),result);return result.toString().strip();
    }
    static void plainNode(Object value,StringBuilder result) {
        if(!(value instanceof Map<?,?> node)) return;
        if(node.get("text") instanceof String text) result.append(text);
        if("attachment".equals(node.get("type")) && node.get("attrs") instanceof Map<?,?> attrs) result.append('[').append(attrs.get("name")).append(']');
        if(node.get("content") instanceof List<?> children) for(Object child:children) plainNode(child,result);
        if(Set.of("paragraph","heading","listItem","codeBlock","hardBreak","tableRow","attachment").contains(node.get("type"))) result.append('\n');
    }
    static Set<UUID> files(Object value) {var result=new LinkedHashSet<UUID>();visit(value,ref->{if(!ref.isBlank())result.add(uuid(ref));});return result;}
    static boolean pending(Object value) {boolean[] pending={false};visit(value,ref->{if(ref.isBlank())pending[0]=true;});return pending[0];}
    private static void visit(Object value,java.util.function.Consumer<String> accept) {
        if(!(value instanceof Map<?,?> node)) return;
        if("shnea-editor".equals(node.get("format"))) {visit(node.get("content"),accept);return;}
        if("attachment".equals(node.get("type")) && node.get("attrs") instanceof Map<?,?> attrs && attrs.get("fileId") instanceof String id) accept.accept(id);
        if(node.get("content") instanceof List<?> children) for(Object child:children) visit(child,accept);
    }
}
