package com.disasterinfo.eodiisseo.ai.summary.adapter;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryRetryPolicy;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
@Component @Profile("!test")
public class ConfiguredSummaryRetry implements SummaryRetryPolicy {
    private final Policy policy;
    public ConfiguredSummaryRetry(@Value("${eodiisseo.ai.summary.max-retries:0}") int retries,
            @Value("${eodiisseo.ai.summary.retry-delay:PT30S}") Duration delay){policy=new Policy(retries,delay);}
    public Optional<Policy> configuredPolicy(){return Optional.of(policy);}
}
