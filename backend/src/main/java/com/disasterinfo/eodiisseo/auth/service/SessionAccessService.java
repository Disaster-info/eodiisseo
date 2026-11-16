package com.disasterinfo.eodiisseo.auth.service;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Existing domains receive verified IDs from their HTTP adapter, then recheck persisted state here. */
@Service @Profile("!test")
public class SessionAccessService {
    private final AuthRepository repository;
    public SessionAccessService(AuthRepository repository){this.repository=repository;}
    @Transactional(readOnly=true)
    public void requireActiveSession(UUID member,UUID session){
        if(member==null || session==null || repository.findUsableSession(session,member)==null)throw ApiProblem.unauthorized();
    }
    @Transactional(readOnly=true)
    public boolean isActiveMember(UUID member){return member!=null && repository.countActiveMember(member)==1;}
    @Transactional
    public void runWithActiveSession(UUID member,UUID session,Runnable action){
        Objects.requireNonNull(action);
        if(member==null || session==null)throw ApiProblem.unauthorized();
        // Same member-before-session order as withdrawal. No AI result can be saved after withdrawal.
        repository.lockMember(member);
        if(repository.lockUsableSession(session,member)==null)throw ApiProblem.unauthorized();
        action.run();
    }
}
