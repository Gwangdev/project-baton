package com.projectbaton.qa;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * README §7: 인용은 서버의 후보에서 다시 만들고, 후보 허용 목록 밖을 인용한 답변은 발행하지 않는다. 글은 있는데
 * 인용이 없는 답변도 근거가 없으므로 발행하지 않는다.
 */
@Component
public class CitationValidator {
    static final int MAX_ANSWER = 4_000;

    /** 인용된 라벨을 중복 없이 인용 순서대로 돌려주고, 발행하면 안 되는 답변이면 예외를 던진다. */
    public List<String> validate(GroundedAnswerGenerator.Generated generated, Set<String> allowedLabels) {
        String answer = generated.answer();
        if (answer == null || answer.length() > MAX_ANSWER) throw new GroundedAnswerGenerator.GenerationException("ANSWER_INVALID", false);
        Set<String> cited = new LinkedHashSet<>(generated.citations());
        if (answer.isBlank() != cited.isEmpty() || !allowedLabels.containsAll(cited)) {
            throw new GroundedAnswerGenerator.GenerationException("CITATION_INVALID", false);
        }
        return List.copyOf(cited);
    }
}
