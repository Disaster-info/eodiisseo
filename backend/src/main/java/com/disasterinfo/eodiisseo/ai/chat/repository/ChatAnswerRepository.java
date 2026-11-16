package com.disasterinfo.eodiisseo.ai.chat.repository;

import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ChatAnswerRepository extends JpaRepository<ChatAnswer, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatAnswer answer where answer.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatAnswer answer where answer.memberId = :memberId")
    int deleteForMember(@Param("memberId") UUID memberId);
}
