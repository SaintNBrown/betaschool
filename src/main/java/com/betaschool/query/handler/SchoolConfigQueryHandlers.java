package com.betaschool.query.handler;

import com.betaschool.infrastructure.persistence.entity.SchoolScoreConfigEntity;
import com.betaschool.infrastructure.persistence.repository.JpaSchoolScoreConfigRepository;
import com.betaschool.query.model.SchoolConfigQuery.GetSchoolScoreConfigQuery;
import com.betaschool.query.model.SchoolConfigQueryResult.GradingBandView;
import com.betaschool.query.model.SchoolConfigQueryResult.SchoolScoreConfig;
import com.betaschool.shared.QueryHandler;
import com.betaschool.tenant.context.SchoolIdInjector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

public class SchoolConfigQueryHandlers {

    @Component
    @RequiredArgsConstructor
    public static class GetSchoolScoreConfigHandler
            implements QueryHandler<GetSchoolScoreConfigQuery, SchoolScoreConfig> {

        private final JpaSchoolScoreConfigRepository configRepo;

        @Override
        public SchoolScoreConfig handle(GetSchoolScoreConfigQuery query) {
            Long schoolId = SchoolIdInjector.require();

            SchoolScoreConfigEntity config = configRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> {
                        // Lazily return the default config if none has been set yet
                        SchoolScoreConfigEntity defaults = new SchoolScoreConfigEntity();
                        defaults.setSchoolId(schoolId);
                        return defaults;
                    });

            List<GradingBandView> bandViews = config.getGradingBands().stream()
                    .map(b -> new GradingBandView(
                            b.getGrade(),
                            b.getMinScore(),
                            b.getMaxScore(),
                            b.getMinScore() + " - " + b.getMaxScore()))
                    .collect(Collectors.toList());

            return new SchoolScoreConfig(
                    schoolId,
                    config.getCaWeight(),
                    config.getExamWeight(),
                    config.getCaWeight() + ":" + config.getExamWeight(),
                    bandViews);
        }
    }
}
