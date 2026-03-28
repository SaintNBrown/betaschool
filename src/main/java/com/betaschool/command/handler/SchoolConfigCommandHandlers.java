package com.betaschool.command.handler;

import com.betaschool.command.model.SchoolConfigCommand.SetGradingScaleCommand;
import com.betaschool.command.model.SchoolConfigCommand.SetReportCardDisplayCommand;
import com.betaschool.command.model.SchoolConfigCommand.SetScoreRatioCommand;
import com.betaschool.infrastructure.persistence.entity.SchoolScoreConfigEntity;
import com.betaschool.infrastructure.persistence.entity.SchoolScoreConfigEntity.GradingBand;
import com.betaschool.infrastructure.persistence.repository.JpaSchoolScoreConfigRepository;
import com.betaschool.shared.CommandHandler;
import com.betaschool.shared.exception.BusinessRuleViolationException;
import com.betaschool.tenant.context.SchoolIdInjector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SchoolConfigCommandHandlers {

    // ── Set CA : Exam ratio ───────────────────────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class SetScoreRatioHandler implements CommandHandler<SetScoreRatioCommand, Void> {

        private final JpaSchoolScoreConfigRepository configRepo;

        @Override
        public Void handle(SetScoreRatioCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (cmd.caWeight() + cmd.examWeight() != 100) {
                throw new BusinessRuleViolationException(
                        "caWeight (" + cmd.caWeight() + ") + examWeight (" + cmd.examWeight()
                        + ") must equal 100");
            }

            SchoolScoreConfigEntity config = configRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> SchoolScoreConfigEntity.builder().schoolId(schoolId).build());
            config.setCaWeight(cmd.caWeight());
            config.setExamWeight(cmd.examWeight());
            configRepo.save(config);
            return null;
        }
    }

    // ── Set grading scale ─────────────────────────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class SetGradingScaleHandler implements CommandHandler<SetGradingScaleCommand, Void> {

        private final JpaSchoolScoreConfigRepository configRepo;

        @Override
        public Void handle(SetGradingScaleCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            List<SetGradingScaleCommand.GradingBandRequest> bands = cmd.bands();

            // ── Validate individual bands ─────────────────────────────────
            for (var band : bands) {
                if (band.minScore() > band.maxScore()) {
                    throw new BusinessRuleViolationException(
                            "Band '" + band.grade() + "': minScore (" + band.minScore()
                            + ") must not exceed maxScore (" + band.maxScore() + ")");
                }
            }

            // ── Unique grade labels ───────────────────────────────────────
            Set<String> labels = new HashSet<>();
            for (var band : bands) {
                if (!labels.add(band.grade().toUpperCase())) {
                    throw new BusinessRuleViolationException(
                            "Duplicate grade label: '" + band.grade() + "'");
                }
            }

            // ── Collective coverage: must cover 0–100, no gaps, no overlaps ──
            List<SetGradingScaleCommand.GradingBandRequest> sorted = bands.stream()
                    .sorted(Comparator.comparingInt(SetGradingScaleCommand.GradingBandRequest::minScore))
                    .collect(Collectors.toList());

            if (sorted.get(0).minScore() != 0) {
                throw new BusinessRuleViolationException(
                        "Grading bands must start from 0 (lowest minScore is "
                        + sorted.get(0).minScore() + ")");
            }
            if (sorted.get(sorted.size() - 1).maxScore() != 100) {
                throw new BusinessRuleViolationException(
                        "Grading bands must reach 100 (highest maxScore is "
                        + sorted.get(sorted.size() - 1).maxScore() + ")");
            }

            for (int i = 1; i < sorted.size(); i++) {
                int prevMax = sorted.get(i - 1).maxScore();
                int currMin = sorted.get(i).minScore();
                if (currMin != prevMax + 1) {
                    throw new BusinessRuleViolationException(
                            "Gap or overlap between bands: '"
                            + sorted.get(i - 1).grade() + "' ends at " + prevMax
                            + " but '" + sorted.get(i).grade() + "' starts at " + currMin
                            + ". Bands must be contiguous with no gaps or overlaps.");
                }
            }

            // ── Persist ───────────────────────────────────────────────────
            List<GradingBand> entities = bands.stream()
                    .map(b -> new GradingBand(b.grade().toUpperCase(), b.minScore(), b.maxScore()))
                    .collect(Collectors.toList());

            SchoolScoreConfigEntity config = configRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> SchoolScoreConfigEntity.builder().schoolId(schoolId).build());
            config.setGradingBands(entities);
            configRepo.save(config);
            return null;
        }
    }

    // ── Set report card display preference ────────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class SetReportCardDisplayHandler
            implements CommandHandler<SetReportCardDisplayCommand, Void> {

        private final JpaSchoolScoreConfigRepository configRepo;

        @Override
        public Void handle(SetReportCardDisplayCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            SchoolScoreConfigEntity config = configRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> SchoolScoreConfigEntity.builder().schoolId(schoolId).build());
            config.setShowStudentPosition(cmd.showStudentPosition());
            configRepo.save(config);
            return null;
        }
    }
}
