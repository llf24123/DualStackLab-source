package org.example.filemanager;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.*;
import io.github.rosemoe.sora.lang.styling.*;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.util.*;
import com.florianingerl.util.regex.*;

final class MtsxLanguage extends EmptyLanguage {
    boolean tabs;
    @Override public boolean useTab(){return tabs;}
    final MtsxSyntax syntax;final Analyzer analyzer;
    MtsxLanguage(MtsxSyntax syntax){this.syntax=syntax;analyzer=new Analyzer();}
    @Override public AnalyzeManager getAnalyzeManager(){return analyzer;}
    @Override public SymbolPairMatch getSymbolPairs(){SymbolPairMatch pairs=new SymbolPairMatch();for(String pair:syntax.brackets)pairs.putPair(pair.charAt(0),new SymbolPairMatch.SymbolPair(pair.substring(0,1),pair.substring(1)));return pairs;}
    void colors(EditorColorScheme scheme){for(MtsxSyntax.Style style:syntax.styles.values()){scheme.setColor(style.id(),Ui.dark?style.dark():style.light());scheme.setColor(style.id()+4096,Ui.dark?style.darkBackground():style.lightBackground());}}
    private long style(int id){for(MtsxSyntax.Style style:syntax.styles.values())if(style.id()==id)return TextStyle.makeStyle(id,(Ui.dark?style.darkBackground():style.lightBackground())==0?0:id+4096,style.bold(),style.italic(),style.strike());return TextStyle.makeStyle(id);}
    private Span span(int column,int id){Span span=Span.obtain(column,style(id));for(MtsxSyntax.Style value:syntax.styles.values())if(value.id()==id&&value.underline())span.setUnderlineColor(Ui.dark?value.dark():value.light());return span;}
    private static final class Budget extends RuntimeException{}
    private static final class Limited implements CharSequence{
        final CharSequence text;final int from,to;final long deadline;final int[] calls;
        Limited(CharSequence text,int from,int to,long deadline,int[] calls){this.text=text;this.from=from;this.to=to;this.deadline=deadline;this.calls=calls;}
        public int length(){return to-from;}
        public char charAt(int at){if(at<0||at>=length())throw new IndexOutOfBoundsException();if((++calls[0]&255)==0&&(calls[0]>500000||System.nanoTime()>deadline))throw new Budget();return text.charAt(from+at);}
        public CharSequence subSequence(int start,int end){if(start<0||end<start||end>length())throw new IndexOutOfBoundsException();return new Limited(text,from+start,from+end,deadline,calls);}
        public String toString(){if(System.nanoTime()>deadline)throw new Budget();return text.subSequence(from,to).toString();}
    }
    private record Hit(MtsxSyntax.Rule rule,Matcher matcher,List<Hit> parts,int start,int end){}
    private record Match(Hit hit,boolean ending,int depth){}
    private Matcher matcher(Pattern pattern,CharSequence input,boolean all){Matcher result=pattern.matcher(input);if(all)result.setMode(Matcher.CAPTURE_TREE);return result;}
    private static void check(Limited input,int depth){
        if(depth>32||++input.calls[0]>500000||System.nanoTime()>input.deadline)throw new Budget();
    }
    private Hit find(MtsxSyntax.Rule rule,Limited input,int position,int boundary,boolean anchored,int depth){
        check(input,depth);
        if(rule.group==null){
            Matcher match=matcher(rule.match,input,rule.allGroups);
            boolean found=match.region(position,boundary).useTransparentBounds(true).useAnchoringBounds(false).find();
            return !found||anchored&&match.start()!=position?null:new Hit(rule,match,List.of(),match.start(),match.end());
        }
        if(rule.group.equals("select")){
            Hit best=null;for(MtsxSyntax.Rule child:rule.children){Hit hit=find(child,input,position,boundary,anchored,depth+1);if(hit!=null&&(best==null||hit.start<best.start))best=hit;}return best;
        }
        int search=position;
        while(search<=boundary){
            check(input,depth);Hit first=find(rule.children.get(0),input,search,boundary,anchored,depth+1);if(first==null)return null;
            List<Hit> parts=new ArrayList<>();parts.add(first);int end=first.end;boolean complete=true;
            for(int i=1;i<rule.children.size();i++){
                Hit next=find(rule.children.get(i),input,end,boundary,true,depth+1);
                if(next==null){complete=false;break;}parts.add(next);end=next.end;
            }
            if(complete||rule.group.equals("link"))return new Hit(rule,null,List.copyOf(parts),first.start,end);
            if(anchored||first.start>=boundary)return null;search=first.start+1;
        }
        return null;
    }
    private void paintCapture(int number,int a,int b,Map<Integer,Integer> groups,Map<Integer,List<MtsxSyntax.Rule>> children,
                              int[] colors,int offset,Limited input,int start,int end,int depth){
        check(input,depth);if(a<start||b>end||a<0||b<a)return;
        Integer color=groups.get(number);if(color!=null)Arrays.fill(colors,offset+a,offset+b,color);
        List<MtsxSyntax.Rule> rules=children.get(number);
        if(rules!=null&&b>a){Limited nested=(Limited)input.subSequence(a,b);scan(nested,rules,new ArrayList<>(),colors,offset+a,colors[offset+a],depth+1);}
    }
    private void repeated(CaptureTreeNode node,Map<Integer,Integer> groups,Map<Integer,List<MtsxSyntax.Rule>> children,
                          int[] colors,int offset,Limited input,int start,int end,int depth){
        check(input,depth);Capture capture=node.getCapture();
        if(capture!=null)paintCapture(node.getGroupNumber(),capture.getStart(),capture.getEnd(),groups,children,colors,offset,input,start,end,depth+1);
        for(CaptureTreeNode child:node.getChildren())repeated(child,groups,children,colors,offset,input,start,end,depth+1);
    }
    private void paint(Hit hit,boolean ending,int[] colors,int offset,Limited input,int depth){
        check(input,depth);Arrays.fill(colors,offset+hit.start,offset+hit.end,hit.rule.style);
        if(hit.matcher==null){for(Hit part:hit.parts)paint(part,false,colors,offset,input,depth+1);return;}
        Map<Integer,Integer> groups=ending?hit.rule.endGroups:hit.rule.groups;
        Map<Integer,List<MtsxSyntax.Rule>> children=ending?hit.rule.endCaptureChildren:hit.rule.captureChildren;
        Set<Integer> numbers=new TreeSet<>(groups.keySet());numbers.addAll(children.keySet());
        for(int number:numbers)paintCapture(number,hit.matcher.start(number),hit.matcher.end(number),groups,children,colors,offset,input,hit.start,hit.end,depth+1);
        if(ending?hit.rule.endAllGroups:hit.rule.allGroups)repeated(hit.matcher.captureTree().getRoot(),groups,children,colors,offset,input,hit.start,hit.end,depth+1);
    }
    private void scan(Limited input,List<MtsxSyntax.Rule> rules,List<MtsxSyntax.Rule> stack,int[] colors,int offset,int fallback,int nesting){
        int length=input.length(),position=0;
        while(position<length){
            check(input,nesting);MtsxSyntax.Rule parent=stack.isEmpty()?null:stack.get(stack.size()-1);int base=parent==null?fallback:parent.inner;
            Match best=null;
            if(parent!=null){Matcher end=matcher(parent.end,input,parent.endAllGroups);if(end.find(position))best=new Match(new Hit(parent,end,List.of(),end.start(),end.end()),true,stack.size()-1);}
            int boundary=length;
            for(int depth=0;depth<stack.size();depth++){MtsxSyntax.Rule outer=stack.get(depth);if(!outer.matchEndFirst)continue;
                Matcher end=matcher(outer.end,input,outer.endAllGroups);if(end.find(position)){boundary=Math.min(boundary,end.start());if(best==null||end.start()<best.hit.start||end.start()==best.hit.start&&depth<best.depth)best=new Match(new Hit(outer,end,List.of(),end.start(),end.end()),true,depth);}}
            int ruleIndex=0;
            for(MtsxSyntax.Rule rule:parent==null?rules:parent.children){
                Hit hit=find(rule,input,position,boundary,false,nesting+1);
                if(hit!=null&&(best==null||hit.start<best.hit.start||best.ending&&best.depth==stack.size()-1&&!parent.matchEndFirst&&hit.start==best.hit.start&&ruleIndex<parent.endPriority))best=new Match(hit,false,-1);ruleIndex++;
            }
            if(best==null){Arrays.fill(colors,offset+position,offset+length,base);break;}
            Hit hit=best.hit;int start=hit.start,end=hit.end;Arrays.fill(colors,offset+position,offset+start,base);
            MtsxSyntax.Rule resolved=hit.rule;
            if(!best.ending&&hit.rule.end!=null)try{resolved=syntax.resolve(hit.rule,hit.matcher);}catch(java.io.IOException invalid){Arrays.fill(colors,offset+start,offset+Math.min(length,Math.max(end,start+1)),base);position=Math.max(end,start+1);continue;}
            paint(hit,best.ending,colors,offset,input,nesting+1);
            if(best.ending)stack.subList(best.depth,stack.size()).clear();else if(hit.rule.end!=null){if(stack.size()+nesting>=32)throw new Budget();stack.add(resolved);}
            if(end==position&&(!best.ending||stack.isEmpty()))position++;else position=end;
        }
        while(!stack.isEmpty()){check(input,nesting);MtsxSyntax.Rule last=stack.get(stack.size()-1);Matcher end=last.end.matcher(input);if(end.find(length)&&end.start()==length&&end.end()==length)stack.remove(stack.size()-1);else break;}
    }
    final class Analyzer extends AsyncIncrementalAnalyzeManager<List<MtsxSyntax.Rule>,Span>{
        public List<MtsxSyntax.Rule> getInitialState(){return List.of();}
        public boolean stateEquals(List<MtsxSyntax.Rule>a,List<MtsxSyntax.Rule>b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++){var x=a.get(i);var y=b.get(i);if(x==y)continue;if(x.origin==null||x.origin!=y.origin||!x.end.pattern().equals(y.end.pattern()))return false;}return true;}
        public LineTokenizeResult<List<MtsxSyntax.Rule>,Span> tokenizeLine(CharSequence line,List<MtsxSyntax.Rule> previous,int index){
            int length=Math.min(line.length(),4096);int[] colors=new int[length];Arrays.fill(colors,EditorColorScheme.TEXT_NORMAL);
            List<MtsxSyntax.Rule> stack=new ArrayList<>(previous);Limited input=new Limited(line,0,length,System.nanoTime()+12_000_000,new int[]{0});
            try{scan(input,syntax.rules,stack,colors,0,EditorColorScheme.TEXT_NORMAL,0);}
            catch(Budget|StackOverflowError tooExpensive){Arrays.fill(colors,EditorColorScheme.TEXT_NORMAL);stack.clear();}
            List<Span> spans=new ArrayList<>();int last=-1;for(int i=0;i<colors.length;i++)if(colors[i]!=last){last=colors[i];spans.add(span(i,last));}
            if(spans.isEmpty())spans.add(Span.obtain(0,TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
            if(line.length()>length)spans.add(Span.obtain(length,TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
            return new LineTokenizeResult<>(List.copyOf(stack),spans,spans);
        }
        public List<Span> generateSpansForLine(LineTokenizeResult<List<MtsxSyntax.Rule>,Span> result){return result.tokens;}
        public List<CodeBlock> computeBlocks(Content text,CodeBlockAnalyzeDelegate delegate){return List.of();}
    }
}
