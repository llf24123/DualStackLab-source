package org.example.filemanager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MtsxCompositionInstrumentedTest {
    private MtsxSyntax syntax(String rules)throws IOException{return MtsxSyntax.parse("{name:[\"Composition45\"] contains:["+rules+"]}");}
    private List<Span> tokens(MtsxSyntax syntax,String line){
        // Warm up regex classes before asserting colors under the real per-line runtime budget.
        MtsxLanguage language=new MtsxLanguage(syntax);language.analyzer.tokenizeLine(line,List.of(),0);
        return language.analyzer.tokenizeLine(line,List.of(),0).spans;
    }
    private int color(List<Span> spans,int at){return TextStyle.getForegroundColorId(spans.stream().filter(s->s.getColumn()<=at).reduce((a,b)->b).orElseThrow().getStyle());}
    @Test public void captureChildrenApplyDefaultAndMultipleRules()throws Exception{
        MtsxSyntax syntax=syntax("{match:/<(.+?)>/,1:\"error\",1:{match:/[a-z]+/,0:\"string\"},1:{match:/\\d+/,0:\"number\"}}");
        var spans=tokens(syntax,"<啊abc123哦>");
        assertEquals(syntax.styles.get("error").id(),color(spans,1));
        assertEquals(syntax.styles.get("string").id(),color(spans,2));
        assertEquals(syntax.styles.get("number").id(),color(spans,5));
        assertEquals(syntax.styles.get("error").id(),color(spans,8));
        assertEquals(EditorColorScheme.TEXT_NORMAL,color(spans,0));
    }
    @Test public void captureBoundariesHaveIndependentAnchorsAndOpaqueLookaround()throws Exception{
        MtsxSyntax syntax=syntax("{match:/<(abc)>/,1:{match:/(?<=<)a|c(?=>)/,0:\"error\"},1:{match:/^a|c$/,0:\"keyword\"}}");
        var spans=tokens(syntax,"<abc>");assertEquals(syntax.styles.get("keyword").id(),color(spans,1));assertEquals(syntax.styles.get("keyword").id(),color(spans,3));
        assertEquals(EditorColorScheme.TEXT_NORMAL,color(spans,2));
    }
    @Test public void emptyAndUnmatchedCaptureChildrenDoNotScanOutside()throws Exception{
        MtsxSyntax syntax=syntax("{match:/x(a)?()/,1:{match:/./,0:\"error\"},2:{match:/^/,0:\"keyword\"}}");
        var spans=tokens(syntax,"xZ");assertEquals(EditorColorScheme.TEXT_NORMAL,color(spans,1));
    }
    @Test public void repeatedCaptureChildrenVisitEveryOccurrence()throws Exception{
        MtsxSyntax syntax=syntax("{match:/(ab)+/,recordAllGroups:true,1:{match:/^a/,0:\"keyword\"},1:{match:/b$/,0:\"string\"}}");
        var spans=tokens(syntax,"ababab");for(int i=0;i<6;i++)assertEquals(syntax.styles.get(i%2==0?"keyword":"string").id(),color(spans,i));
    }
    @Test public void captureChildrenMayContainBoundedStartEndMatchers()throws Exception{
        MtsxSyntax syntax=syntax("{match:/<(.+)>/,1:{start:{match:/\\[/},end:{match:/\\]/},style:\"string\",contains:[{match:/\\d+/,0:\"number\"}]}}");
        var spans=tokens(syntax,"<[12]x>");assertEquals(syntax.styles.get("string").id(),color(spans,1));assertEquals(syntax.styles.get("number").id(),color(spans,2));assertEquals(EditorColorScheme.TEXT_NORMAL,color(spans,5));
    }
    @Test public void linkAllNeedsEveryAdjacentMatcherAndRetriesNextStart()throws Exception{
        MtsxSyntax syntax=syntax("{group:linkAll,contains:[{match:/a+/,0:\"keyword\"},{match:/b+/,0:\"string\"},{match:/c+/,0:\"number\"}]}");
        var spans=tokens(syntax,"aa bb aabbcc");
        assertEquals(EditorColorScheme.TEXT_NORMAL,color(spans,0));assertEquals(EditorColorScheme.TEXT_NORMAL,color(spans,3));
        assertEquals(syntax.styles.get("keyword").id(),color(spans,6));assertEquals(syntax.styles.get("string").id(),color(spans,8));assertEquals(syntax.styles.get("number").id(),color(spans,10));
    }
    @Test public void linkAllowsPrefixAndStopsAtFirstMissingChild()throws Exception{
        MtsxSyntax syntax=syntax("{group:link,contains:[{match:/a+/,0:\"keyword\"},{match:/b+/,0:\"string\"},{match:/c+/,0:\"number\"}]}");
        var spans=tokens(syntax,"aa cc aabb cc");assertEquals(syntax.styles.get("keyword").id(),color(spans,0));assertEquals(EditorColorScheme.TEXT_NORMAL,color(spans,3));assertEquals(syntax.styles.get("string").id(),color(spans,8));
    }
    @Test public void nestedSelectWithinLinkKeepsIndependentCaptureNumbering()throws Exception{
        MtsxSyntax syntax=syntax("{group:linkAll,style:\"operator\",contains:[{match:/\\(/},{group:select,contains:[{match:/(a)\\1/,1:\"keyword\"},{match:/(b)\\1/,1:\"number\"}]},{match:/\\)/}]}");
        var spans=tokens(syntax,"(aa) (bb)");assertEquals(syntax.styles.get("operator").id(),color(spans,0));assertEquals(syntax.styles.get("keyword").id(),color(spans,1));assertEquals(syntax.styles.get("number").id(),color(spans,6));
    }
    @Test public void invalidCompositeRulesFailAtImport()throws Exception{
        for(String rules:List.of(
                "{group:linkAll,contains:[]}",
                "{group:linkAll,contains:[{start:{match:/</},end:{match:/>/}}]}",
                "{match:/(a)/,2:{match:/./}}",
                "{match:/(a)/,1:\"error\",1:\"string\"}")){
            try{syntax(rules);fail("accepted: "+rules);}catch(IOException expected){}
        }
    }
    @Test public void pathologicalCaptureRegexSharesOuterBudget()throws Exception{
        MtsxSyntax syntax=syntax("{match:/<(.*)>/,1:{match:/(a+)+$/}}");
        long start=System.nanoTime();var spans=tokens(syntax,"<"+"a".repeat(3000)+"!>");
        assertTrue("regex budget exceeded",System.nanoTime()-start<2_000_000_000L);assertFalse(spans.isEmpty());
    }
}
