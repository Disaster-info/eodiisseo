package com.disasterinfo.eodiisseo.ai.resource.vector;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort;
import java.util.*;
/** Required companion for EXACT mode: no model-selected subset may drop required context/negation/headings. */
public final class MandatoryContextGenerationGuard implements ChatAnswerGenerationPort {
    private final ChatAnswerGenerationPort delegate;
    public MandatoryContextGenerationGuard(ChatAnswerGenerationPort delegate){this.delegate=Objects.requireNonNull(delegate);}
    public boolean isConfigured(){return delegate.isConfigured();}
    public GeneratedAnswer generate(GenerationRequest request){
        var answer=delegate.generate(request);if(answer==null)return new GeneratedAnswer(null,List.of(),false,null);
        var ids=request.evidence().stream().map(e->e.resourceId()).distinct().toList();
        String exact=request.evidence().stream().flatMap(e->Arrays.stream(RagChunkGroup.BOUNDARY.split(e.content())))
            .collect(java.util.stream.Collectors.joining("\n"));
        if(!answer.grounded()||answer.usedResourceIds()==null||!new LinkedHashSet<>(answer.usedResourceIds()).equals(new LinkedHashSet<>(ids))
            ||answer.usedResourceIds().size()!=ids.size()||!exact.equals(answer.text()))return new GeneratedAnswer(null,List.of(),false,answer.modelName());
        return answer;
    }
}