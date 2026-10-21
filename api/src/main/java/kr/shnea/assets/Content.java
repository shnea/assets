package kr.shnea.assets;

import java.net.URI;
import java.util.*;

record Content(String title, String category, Object work, Object remember, Object start,
               List<String> tags, List<Tool> tools, List<Term> terms, List<Link> references,
               List<UUID> fileIds, Object purpose, Object inputs, Object completion, Object troubleshooting,
               String status, String verifiedOn, String environment) {
    record Tool(String kind,String name,String url,Object note) {}
    record Term(String name,Object explanation,Object example) {}
    record Link(String label,String url) {}
    static String text(String value,int limit) {
        String result=value==null?"":value.strip();
        if(result.length()>limit || result.indexOf('\0')>=0) throw Failure.invalid();
        return result;
    }
    static <T> List<T> list(List<T> value,int limit) {
        if(value==null) return List.of();
        if(value.size()>limit || value.stream().anyMatch(Objects::isNull)) throw Failure.invalid();
        return List.copyOf(value);
    }
    static String url(String value) {
        String result=text(value,2048);
        if(result.isEmpty()) return result;
        try { var uri=URI.create(result); if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw Failure.invalid(); }
        catch(IllegalArgumentException ex) { throw Failure.invalid(); }
        return result;
    }
    Content normalize() {
        String state=text(status,30);
        if(state.isEmpty()) state="UNVERIFIED";
        if(!Set.of("UNVERIFIED","VERIFIED","NEEDS_UPDATE").contains(state)) throw Failure.invalid();
        String date=text(verifiedOn,10);
        if(!date.isEmpty()) {try {java.time.LocalDate.parse(date);} catch(Exception e) {throw Failure.invalid();}}
        var cleanTools=list(tools,100).stream().map(t->new Tool(text(t.kind,40),text(t.name,200),url(t.url),RichText.normalize(t.note,20000))).toList();
        var cleanTerms=list(terms,100).stream().map(t->new Term(text(t.name,100),RichText.normalize(t.explanation,20000),RichText.normalize(t.example,20000))).toList();
        var cleanLinks=list(references,100).stream().map(l->new Link(text(l.label,200),url(l.url))).toList();
        return new Content(text(title,200),text(category,100),RichText.normalize(work,100000),RichText.normalize(remember,100000),RichText.normalize(start,100000),
            list(tags,50).stream().map(t->text(t,100)).distinct().toList(),cleanTools,cleanTerms,cleanLinks,
            list(fileIds,100).stream().distinct().toList(),RichText.normalize(purpose,20000),RichText.normalize(inputs,20000),RichText.normalize(completion,20000),
            RichText.normalize(troubleshooting,20000),state,date,text(environment,2000));
    }
    boolean hasContent() {
        return java.util.stream.Stream.of(work,remember,start).anyMatch(v->!RichText.plain(v).isBlank());
    }
    String displayTitle() {
        if(!title.isBlank()) return title;
        return java.util.stream.Stream.of(work,remember,start).map(RichText::plain).filter(v->!v.isBlank()).findFirst()
            .map(v->v.lines().findFirst().orElse(v)).map(v->v.substring(0,Math.min(40,v.length()))).orElse("이름 없는 제작법");
    }
    List<Object> richValues() {
        var values=new ArrayList<>(List.of(work,remember,start,purpose,inputs,completion,troubleshooting));
        for(var tool:tools) values.add(tool.note());
        for(var term:terms) {values.add(term.explanation());values.add(term.example());}
        return values;
    }
    Set<UUID> inlineFiles() {var ids=new LinkedHashSet<UUID>();for(var value:richValues())ids.addAll(RichText.files(value));return ids;}
    boolean pendingAttachments() {return richValues().stream().anyMatch(RichText::pending);}
}
