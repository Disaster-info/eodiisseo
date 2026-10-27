package com.disasterinfo.eodiisseo.ai.chat.repository;

import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswerResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ChatAnswerResourceRepository extends JpaRepository<ChatAnswerResource, ChatAnswerResource.Key> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatAnswerResource link where link.answerId in "
            + "(select answer.answerId from ChatAnswer answer where answer.expiresAt <= :now)")
    int deleteForExpiredAnswers(@Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatAnswerResource link where link.answerId in "
            + "(select answer.answerId from ChatAnswer answer where answer.memberId = :memberId)")
    int deleteForMemberAnswers(@Param("memberId") UUID memberId);
}
