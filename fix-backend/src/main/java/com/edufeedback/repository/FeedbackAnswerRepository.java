package com.edufeedback.repository;

import com.edufeedback.model.FeedbackAnswer;
import com.edufeedback.model.FeedbackResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedbackAnswerRepository extends JpaRepository<FeedbackAnswer, Long> {
    List<FeedbackAnswer> findByResponse(FeedbackResponse response);
}
