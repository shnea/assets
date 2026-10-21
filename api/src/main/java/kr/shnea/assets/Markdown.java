package kr.shnea.assets;

import java.util.*;
import java.util.function.Function;

/** Portable text for downloads; the editor JSON remains the canonical document. */
final class Markdown {
    private Markdown() {}

    static String text(Object value, Function<UUID,String> attachment) {
        if(value instanceof String string) return string;
        if(!(value instanceof Map<?,?> root)) return "";
        return node(root.get("content"),attachment).strip();
    }

    static String escape(String text) {
        return text.replace("\\","\\\\").replace("`","\\`").replace("*","\\*")
            .replace("_","\\_").replace("[","\\[").replace("]","\\]").replace("<","&lt;").replace(">","&gt;");
    }

    static String node(Object value,Function<UUID,String> attachment) {
        if(!(value instanceof Map<?,?> map)) return "";
        String type=String.valueOf(map.get("type"));
        Map<?,?> attrs=map.get("attrs") instanceof Map<?,?> a?a:Map.of();
        List<?> children=map.get("content") instanceof List<?> list?list:List.of();
        String joined=children.stream().map(child->node(child,attachment)).reduce("",String::concat);
        return switch(type) {
            case "text" -> marked(map.get("text") instanceof String string?string:"",map.get("marks"));
            case "paragraph" -> joined+"\n\n";
            case "heading" -> "#".repeat(attrs.get("level") instanceof Number level?Math.clamp(level.intValue(),1,6):2)+" "+joined+"\n\n";
            case "hardBreak" -> "  \n";
            case "horizontalRule" -> "\n---\n\n";
            case "blockquote" -> joined.strip().lines().map(line->"> "+line).reduce("",(a,b)->a+b+"\n")+"\n";
            case "codeBlock" -> {
                String raw=raw(map),fence="```";
                while(raw.contains(fence)) fence+="`";
                String language=attrs.get("language") instanceof String s?s.replaceAll("[^a-zA-Z0-9_+-]",""):"";
                yield fence+language+"\n"+raw+"\n"+fence+"\n\n";
            }
            case "bulletList","orderedList","taskList" -> {
                var lines=new StringBuilder();int index=attrs.get("start") instanceof Number n?n.intValue():1;
                for(Object child:children) {
                    String prefix=type.equals("orderedList")?(index++)+". ":"- ";
                    String item=node(child,attachment).strip();
                    lines.append(prefix).append(item.replace("\n","\n"+" ".repeat(prefix.length()))).append('\n');
                }
                yield lines+"\n";
            }
            case "taskItem" -> (Boolean.TRUE.equals(attrs.get("checked"))?"[x] ":"[ ] ")+joined.strip()+"\n";
            case "listItem" -> joined.strip()+"\n";
            case "table" -> {
                var result=new StringBuilder();boolean first=true;
                for(Object row:children) {
                    if(!(row instanceof Map<?,?> r) || !(r.get("content") instanceof List<?> cells)) continue;
                    result.append("| ").append(cells.stream().map(cell->node(cell,attachment).strip().replace("|","\\|").replace("\n","<br>"))
                        .reduce((a,b)->a+" | "+b).orElse("")).append(" |\n");
                    if(first) {result.append("| ").append(String.join(" | ",Collections.nCopies(cells.size(),"---"))).append(" |\n");first=false;}
                }
                yield result+"\n";
            }
            case "attachment" -> {
                String name=escape(String.valueOf(attrs.get("name")));
                String id=String.valueOf(attrs.get("fileId"));
                if(id.isBlank()) yield "[업로드 미완료: "+name+"]\n\n";
                String path=attachment.apply(UUID.fromString(id));
                yield ("image".equals(attrs.get("kind"))?"!":"")+"["+name+"]("+path+")\n\n";
            }
            case "externalImage" -> "!["+escape(attrs.get("alt") instanceof String alt?alt:"")+"]("+String.valueOf(attrs.get("source"))+ ")\n\n";
            default -> joined;
        };
    }

    static String raw(Map<?,?> node) {
        if(node.get("text") instanceof String text) return text;
        if(node.get("content") instanceof List<?> children) return children.stream().filter(Map.class::isInstance).map(child->raw((Map<?,?>)child)).reduce("",String::concat);
        return "";
    }

    static String marked(String text,Object marks) {
        String result=escape(text);
        if(!(marks instanceof List<?> list)) return result;
        for(Object mark:list) {
            if(!(mark instanceof Map<?,?> m)) continue;
            result=switch(String.valueOf(m.get("type"))) {
                case "bold" -> "**"+result+"**";
                case "italic" -> "*"+result+"*";
                case "strike" -> "~~"+result+"~~";
                case "underline" -> "<u>"+result+"</u>";
                case "code" -> {
                    String fence="`";while(text.contains(fence)) fence+="`";
                    yield fence+" "+text+" "+fence;
                }
                case "link" -> m.get("attrs") instanceof Map<?,?> a?"["+result+"]("+a.get("href")+")":result;
                default -> result;
            };
        }
        return result;
    }
}
