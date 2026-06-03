package com.betaschool.command.handler;

import com.betaschool.command.model.TimetableCommand.*;
import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity.DayOfWeek;
import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity.SlotType;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.shared.CommandHandler;
import com.betaschool.shared.exception.BusinessRuleViolationException;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.SchoolIdInjector;
import com.betaschool.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
@Transactional
public class GenerateTimetableHandler
        implements CommandHandler<GenerateTimetableCommand, Long> {

    private static final int MAX_PHASE1_ATTEMPTS = 100;
    private static final int MAX_PHASE2_ATTEMPTS = 50;

    private final JpaClassSessionRepository  classSessionRepo;
    private final JpaClassSubjectRepository  classSubjectRepo;
    private final JpaTimetableSlotRepository slotRepo;
    private final JpaTimetableRepository     timetableRepo;

    // ─────────────────────────────────────────────────────────────────────
    //  Entry point
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public Long handle(GenerateTimetableCommand cmd) {
        Long schoolId = SchoolIdInjector.require();
        Long currentUser = TenantContext.getUserId();

        ClassSessionEntity classSession =
                classSessionRepo.findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "ClassSession", cmd.classSessionId()));

        List<DayOfWeek> operatingDays = parseOperatingDays(cmd.operatingDays());

        Map<Long, Set<DayOfWeek>> unavailable =
                buildUnavailabilityMap(cmd.teacherUnavailableDays());

        List<ClassSubjectEntity> classSubjects =
                classSubjectRepo.findByClassSessionIdAndSchoolId(
                        cmd.classSessionId(), schoolId);
        Map<Long, ClassSubjectEntity> subjectById = classSubjects.stream()
                .collect(Collectors.toMap(ClassSubjectEntity::getId, s -> s));

        List<Occurrence> occurrences = buildOccurrences(
                cmd.subjectFrequencies(), subjectById, operatingDays.size());

        // Build base template from GLOBAL activities only
        DayTemplate baseTemplate = buildBaseTemplate(
                cmd.globalActivities(), cmd.slotDurationMinutes(), cmd.schoolStartTime());

        // Compute per-day subject slot capacity (after day-specific activity replacements)
        Map<DayOfWeek, Integer> perDaySlots = computePerDaySlots(
                cmd, operatingDays, baseTemplate, occurrences);

        // Build per-day templates by applying day-specific replacements to base template
        Map<DayOfWeek, DayTemplate> templates = buildDayTemplates(
                cmd, operatingDays, baseTemplate, perDaySlots);

        validateFeasibility(occurrences, operatingDays, perDaySlots);

        List<OccupiedInterval> occupied =
                loadOccupiedIntervals(schoolId, cmd.classSessionId());

        // ── Phase 1: day assignment ───────────────────────────────────────
        Map<DayOfWeek, List<Occurrence>> dayAssignment = null;
        Random rng = new Random();

        for (int a = 0; a < MAX_PHASE1_ATTEMPTS && dayAssignment == null; a++) {
            dayAssignment = phase1DayAssign(
                    occurrences, operatingDays, perDaySlots,
                    unavailable, occupied, baseTemplate, rng);
        }

        if (dayAssignment == null) {
            throw new BusinessRuleViolationException(
                    "Could not assign subjects to days after " + MAX_PHASE1_ATTEMPTS
                            + " attempts.\n" + diagnose(occurrences, subjectById,
                            operatingDays, unavailable, occupied, baseTemplate));
        }

        // ── Phase 2: slot placement within each day ───────────────────────
        Map<DayOfWeek, List<PlacedSlot>> placed = null;
        for (int a = 0; a < MAX_PHASE2_ATTEMPTS && placed == null; a++) {
            placed = phase2SlotPlace(dayAssignment, operatingDays, templates,
                    subjectById, occupied, rng);
        }

        if (placed == null) {
            throw new BusinessRuleViolationException(
                    "Could not arrange slots within days after " + MAX_PHASE2_ATTEMPTS
                            + " attempts. Adjust subject frequencies or teacher assignments.");
        }

        // ── Persist ───────────────────────────────────────────────────────
        timetableRepo.deactivateAllForClassSession(cmd.classSessionId(), schoolId);
        int nextVersion = timetableRepo.findMaxVersionByClassSessionIdAndSchoolId(
                cmd.classSessionId(), schoolId) + 1;
        String notes = (cmd.notes() != null && !cmd.notes().isBlank())
                ? cmd.notes() : "Auto-generated v" + nextVersion;

        TimetableEntity timetable = timetableRepo.save(TimetableEntity.builder()
                .schoolId(schoolId).classSession(classSession)
                .version(nextVersion).active(true).notes(notes)
                .createdBy(currentUser).build());

        List<TimetableSlotEntity> entities = buildEntities(
                placed, operatingDays, templates, schoolId, subjectById);
        entities.forEach(s -> s.setTimetable(timetable));
        slotRepo.saveAll(entities);

        log.info("Generated timetable id={} v={} classSession={} school={}",
                timetable.getId(), nextVersion, cmd.classSessionId(), schoolId);
        return timetable.getId();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Base template from global activities
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds the base template using ONLY global activities.
     * This template is IDENTICAL for all operating days.
     * Subject slots are represented as placeholders with calculated times.
     */
    private DayTemplate buildBaseTemplate(
            List<GenerateTimetableCommand.GlobalActivitySpec> globalActivities,
            int slotDurationMinutes,
            LocalTime startTime) {

        if (globalActivities == null || globalActivities.isEmpty()) {
            // No global activities — just one subject slot placeholder
            LocalTime end = startTime.plusMinutes(slotDurationMinutes);
            return new DayTemplate(List.of(new DaySlot(DaySlotType.SUBJECT, startTime, end, null)), 1);
        }

        // Sort activities by afterSlotNumber
        List<GenerateTimetableCommand.GlobalActivitySpec> sorted = new ArrayList<>(globalActivities);
        sorted.sort(Comparator.comparingInt(GenerateTimetableCommand.GlobalActivitySpec::afterSlotNumber));

        LocalTime cursor = startTime;
        List<DaySlot> slots = new ArrayList<>();
        int subjectCount = 0;
        int actIdx = 0;

        // Activities before any subjects (afterSlotNumber = 0)
        while (actIdx < sorted.size() && sorted.get(actIdx).afterSlotNumber() == 0) {
            var act = sorted.get(actIdx++);
            int durationMins = (int) Math.round(act.durationSlots() * slotDurationMinutes);
            LocalTime end = cursor.plusMinutes(durationMins);
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, act.label()));
            cursor = end;
        }

        // We don't know how many subject slots yet — will be adjusted per day
        // For base template, we just record the activity positions relative to subject slot indices
        // We'll store activity anchors for later expansion

        // For now, build a simplified representation that captures the sequence
        List<BaseTemplateElement> elements = new ArrayList<>();
        cursor = startTime;
        actIdx = 0;
        int nextActivityAt = 0;

        while (actIdx < sorted.size()) {
            var act = sorted.get(actIdx);
            if (act.afterSlotNumber() == nextActivityAt) {
                int durationMins = (int) Math.round(act.durationSlots() * slotDurationMinutes);
                LocalTime actEnd = cursor.plusMinutes(durationMins);
                elements.add(new BaseTemplateElement(act.label(), durationMins, true));
                cursor = actEnd;
                actIdx++;
            } else {
                // Subject slot
                LocalTime slotEnd = cursor.plusMinutes(slotDurationMinutes);
                elements.add(new BaseTemplateElement(null, slotDurationMinutes, false));
                cursor = slotEnd;
                nextActivityAt++;
            }
        }

        // Return a DayTemplate with expanded slots
        cursor = startTime;
        List<DaySlot> expandedSlots = new ArrayList<>();
        for (BaseTemplateElement elem : elements) {
            LocalTime end = cursor.plusMinutes(elem.durationMinutes);
            if (elem.isActivity) {
                expandedSlots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, elem.label));
            } else {
                expandedSlots.add(new DaySlot(DaySlotType.SUBJECT, cursor, end, null));
            }
            cursor = end;
        }

        int subjectSlotCount = (int) elements.stream().filter(e -> !e.isActivity).count();
        return new DayTemplate(expandedSlots, subjectSlotCount);
    }

    private record BaseTemplateElement(String label, int durationMinutes, boolean isActivity) {}

    // ─────────────────────────────────────────────────────────────────────
    //  Per-day slot computation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Computes how many subject slots each operating day gets.
     *
     * Base subject slots come from either:
     *   - If schoolClosingTime provided: derived from available minutes
     *   - Otherwise: derived from total periods needed across the week
     *
     * Then day-specific activity replacements reduce the count for specific days.
     */
    private Map<DayOfWeek, Integer> computePerDaySlots(
            GenerateTimetableCommand cmd,
            List<DayOfWeek> operatingDays,
            DayTemplate baseTemplate,
            List<Occurrence> occurrences) {

        int slotMins = cmd.slotDurationMinutes();
        int numDays = operatingDays.size();

        // Base subject slots before day-specific replacements
        int baseSlots;
        if (cmd.schoolClosingTime() != null) {
            long totalDayMins = java.time.Duration.between(
                    cmd.schoolStartTime(), cmd.schoolClosingTime()).toMinutes();
            if (totalDayMins <= 0) {
                throw new BusinessRuleViolationException(
                        "schoolClosingTime must be after schoolStartTime.");
            }
            // Calculate how many full subject slots fit in the day
            // Subtract global activity time from base template
            long globalActivityMins = baseTemplate.slots().stream()
                    .filter(s -> s.type() == DaySlotType.ACTIVITY)
                    .mapToLong(s -> java.time.Duration.between(s.start(), s.end()).toMinutes())
                    .sum();
            long availableMins = totalDayMins - globalActivityMins;
            if (availableMins <= 0) {
                throw new BusinessRuleViolationException(
                        "Global activities consume the entire school day.");
            }
            baseSlots = (int) (availableMins / slotMins);
        } else {
            int totalPeriods = occurrences.stream()
                    .mapToInt(o -> o.isDouble() ? 2 : 1).sum();
            baseSlots = Math.max(1, (int) Math.ceil((double) totalPeriods / numDays) + 2);
        }

        // Apply day-specific slot replacements
        Map<DayOfWeek, Integer> result = new LinkedHashMap<>();
        Map<DayOfWeek, Integer> replacementsPerDay = new HashMap<>();

        if (cmd.daySpecificActivities() != null) {
            for (var act : cmd.daySpecificActivities()) {
                for (String dayName : act.onlyOnDays()) {
                    try {
                        DayOfWeek day = DayOfWeek.valueOf(dayName.toUpperCase().trim());
                        replacementsPerDay.merge(day, act.replacesSlots(), Integer::sum);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        for (DayOfWeek day : operatingDays) {
            int replaced = replacementsPerDay.getOrDefault(day, 0);
            int slots = Math.max(baseSlots - replaced, 1);
            result.put(day, slots);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Day template building with replacements
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds per-day templates by applying day-specific activity replacements
     * to the shared base template.
     */
    private Map<DayOfWeek, DayTemplate> buildDayTemplates(
            GenerateTimetableCommand cmd,
            List<DayOfWeek> operatingDays,
            DayTemplate baseTemplate,
            Map<DayOfWeek, Integer> perDaySlots) {

        Map<DayOfWeek, DayTemplate> result = new LinkedHashMap<>();

        // Group day-specific activities by day
        Map<DayOfWeek, List<GenerateTimetableCommand.DaySpecificActivitySpec>> byDay = new HashMap<>();
        if (cmd.daySpecificActivities() != null) {
            for (var act : cmd.daySpecificActivities()) {
                for (String dayName : act.onlyOnDays()) {
                    try {
                        DayOfWeek day = DayOfWeek.valueOf(dayName.toUpperCase().trim());
                        byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(act);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            // Sort each day's activities by afterSlotNumber
            for (var entry : byDay.entrySet()) {
                entry.getValue().sort(Comparator.comparingInt(
                        GenerateTimetableCommand.DaySpecificActivitySpec::afterSlotNumber));
            }
        }

        for (DayOfWeek day : operatingDays) {
            int targetSlots = perDaySlots.get(day);
            List<GenerateTimetableCommand.DaySpecificActivitySpec> daySpecific =
                    byDay.getOrDefault(day, List.of());

            DayTemplate dayTemplate = applyReplacements(
                    baseTemplate, daySpecific, targetSlots, cmd.slotDurationMinutes());
            result.put(day, dayTemplate);
        }
        return result;
    }

    /**
     * Applies day-specific activity replacements to the base template.
     *
     * Day-specific activities REPLACE subject slots at specific positions.
     * The afterSlotNumber refers to the position among REMAINING subject slots
     * after previous replacements.
     */
    private DayTemplate applyReplacements(
            DayTemplate baseTemplate,
            List<GenerateTimetableCommand.DaySpecificActivitySpec> replacements,
            int targetSubjectSlots,
            int slotDurationMinutes) {

        if (replacements.isEmpty()) {
            // No replacements — just trim/pad subject slots to target count
            return trimToSubjectSlots(baseTemplate, targetSubjectSlots);
        }

        List<DaySlot> resultSlots = new ArrayList<>();
        int subjectCount = 0;
        int replaceIdx = 0;
        LocalTime cursor = baseTemplate.slots().get(0).start();

        // Separate last-of-day activities
        List<GenerateTimetableCommand.DaySpecificActivitySpec> lastActivities = replacements.stream()
                .filter(GenerateTimetableCommand.DaySpecificActivitySpec::isLast)
                .toList();
        List<GenerateTimetableCommand.DaySpecificActivitySpec> positioned = replacements.stream()
                .filter(r -> !r.isLast())
                .toList();

        for (DaySlot slot : baseTemplate.slots()) {
            if (slot.type() == DaySlotType.ACTIVITY) {
                // Global activity — keep as-is
                resultSlots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, slot.end(), slot.label()));
                cursor = slot.end();
            } else {
                // SUBJECT slot
                // Check if this position should be replaced
                if (replaceIdx < positioned.size()
                        && positioned.get(replaceIdx).afterSlotNumber() == subjectCount) {
                    var act = positioned.get(replaceIdx);
                    int durationMins = act.replacesSlots() * slotDurationMinutes;
                    LocalTime actEnd = cursor.plusMinutes(durationMins);
                    resultSlots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, actEnd, act.label()));
                    cursor = actEnd;
                    replaceIdx++;
                    subjectCount += act.replacesSlots();
                } else if (subjectCount < targetSubjectSlots) {
                    // Regular subject slot
                    LocalTime slotEnd = cursor.plusMinutes(slotDurationMinutes);
                    resultSlots.add(new DaySlot(DaySlotType.SUBJECT, cursor, slotEnd, null));
                    cursor = slotEnd;
                    subjectCount++;
                }
                // If subjectCount >= targetSubjectSlots, skip remaining subject slots
            }
        }

        // Add last-of-day activities at the end
        for (var act : lastActivities) {
            int durationMins = act.replacesSlots() * slotDurationMinutes;
            LocalTime actEnd = cursor.plusMinutes(durationMins);
            resultSlots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, actEnd, act.label()));
            cursor = actEnd;
        }

        return new DayTemplate(resultSlots, targetSubjectSlots);
    }

    /**
     * Trims or pads the base template to have exactly targetSubjectSlots subject slots.
     */
    private DayTemplate trimToSubjectSlots(DayTemplate base, int targetSubjectSlots) {
        List<DaySlot> result = new ArrayList<>();
        int subjectCount = 0;
        LocalTime cursor = base.slots().get(0).start();

        for (DaySlot slot : base.slots()) {
            if (slot.type() == DaySlotType.ACTIVITY) {
                result.add(new DaySlot(DaySlotType.ACTIVITY, cursor, slot.end(), slot.label()));
                cursor = slot.end();
            } else if (subjectCount < targetSubjectSlots) {
                LocalTime slotEnd = cursor.plusMinutes(
                        (int) java.time.Duration.between(slot.start(), slot.end()).toMinutes());
                result.add(new DaySlot(DaySlotType.SUBJECT, cursor, slotEnd, null));
                cursor = slotEnd;
                subjectCount++;
            }
            // Skip additional subject slots beyond target
        }
        return new DayTemplate(result, targetSubjectSlots);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phase 1 — Day assignment
    // ─────────────────────────────────────────────────────────────────────

    private Map<DayOfWeek, List<Occurrence>> phase1DayAssign(
            List<Occurrence> occurrences, List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> perDaySlots,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied,
            DayTemplate baseTemplate, Random rng) {

        Map<DayOfWeek, Integer> freeSlots = new LinkedHashMap<>(perDaySlots);
        Map<Long, Set<DayOfWeek>> subjectDays = new HashMap<>();
        Map<DayOfWeek, List<Occurrence>> result = new LinkedHashMap<>();
        for (DayOfWeek d : operatingDays) result.put(d, new ArrayList<>());

        List<Occurrence> ordered = sortByConstrainedness(
                occurrences, operatingDays, perDaySlots,
                unavailable, occupied, baseTemplate, rng);

        return p1Backtrack(ordered, 0, result, freeSlots, subjectDays,
                operatingDays, perDaySlots, unavailable, occupied, baseTemplate);
    }

    private Map<DayOfWeek, List<Occurrence>> p1Backtrack(
            List<Occurrence> occs, int idx,
            Map<DayOfWeek, List<Occurrence>> assignment,
            Map<DayOfWeek, Integer> freeSlots,
            Map<Long, Set<DayOfWeek>> subjectDays,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> perDaySlots,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied,
            DayTemplate baseTemplate) {

        if (idx == occs.size()) return assignment;
        Occurrence occ = occs.get(idx);
        int needed = occ.isDouble() ? 2 : 1;
        Long teacherId = occ.teacherId();

        List<DayOfWeek> shuffledDays = new ArrayList<>(operatingDays);
        Collections.shuffle(shuffledDays);

        for (DayOfWeek day : shuffledDays) {
            if (subjectDays.getOrDefault(occ.classSubjectId(), Set.of()).contains(day))
                continue;
            if (teacherId != null
                    && unavailable.getOrDefault(teacherId, Set.of()).contains(day))
                continue;
            if (freeSlots.getOrDefault(day, 0) < needed) continue;
            if (teacherId != null && isTeacherFullyBlockedOnDay(
                    teacherId, day, needed, baseTemplate, occupied)) continue;

            assignment.get(day).add(occ);
            freeSlots.merge(day, -needed, Integer::sum);
            subjectDays.computeIfAbsent(occ.classSubjectId(), k -> new HashSet<>()).add(day);

            var sub = p1Backtrack(occs, idx + 1, assignment, freeSlots, subjectDays,
                    operatingDays, perDaySlots, unavailable, occupied, baseTemplate);
            if (sub != null) return sub;

            assignment.get(day).remove(occ);
            freeSlots.merge(day, needed, Integer::sum);
            subjectDays.get(occ.classSubjectId()).remove(day);
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phase 2 — Slot placement within each day
    // ─────────────────────────────────────────────────────────────────────

    private Map<DayOfWeek, List<PlacedSlot>> phase2SlotPlace(
            Map<DayOfWeek, List<Occurrence>> dayAssignment,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, DayTemplate> templates,
            Map<Long, ClassSubjectEntity> subjectById,
            List<OccupiedInterval> occupied, Random rng) {

        Map<DayOfWeek, List<PlacedSlot>> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            List<Occurrence> occs = dayAssignment.getOrDefault(day, List.of());
            if (occs.isEmpty()) {
                result.put(day, List.of());
                continue;
            }
            List<PlacedSlot> placed = p2PlaceDay(
                    day, occs, templates.get(day), subjectById, occupied, rng);
            if (placed == null) return null;
            result.put(day, placed);
        }
        return result;
    }

    private List<PlacedSlot> p2PlaceDay(
            DayOfWeek day, List<Occurrence> occs, DayTemplate template,
            Map<Long, ClassSubjectEntity> subjectById,
            List<OccupiedInterval> occupied, Random rng) {

        int n = template.subjectSlotCount();
        Long[] slotAssign = new Long[n];
        boolean[] isDouble = new boolean[n];

        Set<Integer> activityBoundaries = activityBoundarySet(template);

        List<Occurrence> doubles = occs.stream().filter(Occurrence::isDouble)
                .collect(Collectors.toList());
        List<Occurrence> singles = occs.stream().filter(o -> !o.isDouble())
                .collect(Collectors.toList());
        Collections.shuffle(doubles, rng);
        Collections.shuffle(singles, rng);

        // Place doubles
        for (Occurrence d : doubles) {
            Long tid = d.teacherId();
            List<Integer> candidates = buildDoubleCandidates(
                    n, slotAssign, activityBoundaries, tid, day, template, occupied);
            if (candidates.isEmpty()) return null;
            int pos = candidates.get(0);
            slotAssign[pos] = d.classSubjectId();
            slotAssign[pos + 1] = d.classSubjectId();
            isDouble[pos] = true;
            isDouble[pos + 1] = true;
        }

        // Place singles
        List<Integer> freePos = new ArrayList<>();
        for (int i = 0; i < n; i++) if (slotAssign[i] == null) freePos.add(i);
        Collections.shuffle(freePos, rng);

        for (Occurrence s : singles) {
            boolean placed = false;
            Long tid = s.teacherId();
            for (int pos : freePos) {
                if (slotAssign[pos] != null) continue;
                if (tid != null && hasConflict(
                        occupied, tid, day, template.subjectSlotTime(pos))) continue;
                slotAssign[pos] = s.classSubjectId();
                placed = true;
                break;
            }
            if (!placed) return null;
        }

        reorderForTeacherTransition(slotAssign, isDouble, subjectById, n);

        List<PlacedSlot> result = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (slotAssign[i] != null)
                result.add(new PlacedSlot(slotAssign[i], i, isDouble[i]));
        return result;
    }

    private Set<Integer> activityBoundarySet(DayTemplate template) {
        Set<Integer> boundaries = new HashSet<>();
        int subIdx = -1;
        for (DaySlot ds : template.slots()) {
            if (ds.type() == DaySlotType.SUBJECT) {
                subIdx++;
            } else if (subIdx >= 0) {
                boundaries.add(subIdx);
            }
        }
        return boundaries;
    }

    private List<Integer> buildDoubleCandidates(
            int n, Long[] slotAssign, Set<Integer> activityBoundaries,
            Long teacherId, DayOfWeek day, DayTemplate template,
            List<OccupiedInterval> occupied) {

        List<Integer> preferred = new ArrayList<>();
        List<Integer> normal = new ArrayList<>();

        for (int pos = 0; pos < n - 1; pos++) {
            if (slotAssign[pos] != null || slotAssign[pos + 1] != null) continue;
            if (activityBoundaries.contains(pos)) continue;
            if (teacherId != null) {
                if (hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos))
                        || hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos + 1)))
                    continue;
            }
            if (activityBoundaries.contains(pos + 1)) preferred.add(pos);
            else normal.add(pos);
        }
        List<Integer> result = new ArrayList<>(preferred);
        result.addAll(normal);
        return result;
    }

    private void reorderForTeacherTransition(
            Long[] slots, boolean[] isDouble,
            Map<Long, ClassSubjectEntity> subjectById, int n) {

        boolean improved = true;
        int passes = 0;
        while (improved && passes++ < n * 2) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                if (slots[i] == null || slots[i + 1] == null) continue;
                if (slots[i].equals(slots[i + 1])) continue;
                Long tA = getTeacherId(subjectById.get(slots[i]));
                Long tB = getTeacherId(subjectById.get(slots[i + 1]));
                if (tA == null || !tA.equals(tB)) continue;
                if (i + 2 >= n || slots[i + 2] == null) continue;
                if (isDouble[i + 1] && slots[i + 1].equals(slots[i + 2])) continue;
                if (isDouble[i + 2] && slots[i + 1].equals(slots[i + 2])) continue;
                Long tC = getTeacherId(subjectById.get(slots[i + 2]));
                if (tC != null && !tC.equals(tA)) {
                    Long tmp = slots[i + 1];
                    slots[i + 1] = slots[i + 2];
                    slots[i + 2] = tmp;
                    boolean tmpB = isDouble[i + 1];
                    isDouble[i + 1] = isDouble[i + 2];
                    isDouble[i + 2] = tmpB;
                    improved = true;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Occurrence building
    // ─────────────────────────────────────────────────────────────────────

    private List<Occurrence> buildOccurrences(
            List<GenerateTimetableCommand.SubjectFrequency> frequencies,
            Map<Long, ClassSubjectEntity> subjectById, int numDays) {

        List<Occurrence> all = new ArrayList<>();
        Set<Long> covered = new HashSet<>();

        for (var freq : frequencies) {
            Long csId = freq.classSubjectId();
            if (!subjectById.containsKey(csId)) {
                throw new BusinessRuleViolationException(
                        "ClassSubject id=" + csId + " not in this class-session.");
            }
            int periods = freq.periodsPerWeek();
            if (periods < 1) {
                throw new BusinessRuleViolationException(
                        "periodsPerWeek ≥ 1 required for classSubjectId=" + csId);
            }

            ClassSubjectEntity cs = subjectById.get(csId);
            boolean forceDouble = Boolean.TRUE.equals(freq.forceDouble());
            List<Occurrence> occs = deriveOccurrences(csId, getTeacherId(cs), periods, forceDouble);

            if (occs.size() > numDays) {
                throw new BusinessRuleViolationException(
                        "Subject id=" + csId + " needs " + occs.size()
                                + " occurrences but only " + numDays + " days available.");
            }
            all.addAll(occs);
            covered.add(csId);
        }

        List<String> omitted = subjectById.values().stream()
                .filter(cs -> !covered.contains(cs.getId()))
                .map(cs -> "'" + (cs.getSubject() != null
                        ? cs.getSubject().getName() : "id=" + cs.getId())
                        + "' (classSubjectId=" + cs.getId() + ")")
                .collect(Collectors.toList());
        if (!omitted.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "Missing from subjectFrequencies: " + String.join(", ", omitted));
        }
        return all;
    }

    private List<Occurrence> deriveOccurrences(
            Long csId, Long teacherId, int periods, boolean forceDouble) {

        List<Occurrence> r = new ArrayList<>();
        if (periods == 1) {
            r.add(new Occurrence(csId, teacherId, false));
        } else if (periods == 2) {
            if (forceDouble) {
                r.add(new Occurrence(csId, teacherId, true));
            } else {
                r.add(new Occurrence(csId, teacherId, false));
                r.add(new Occurrence(csId, teacherId, false));
            }
        } else {
            for (int i = 0; i < periods / 2; i++)
                r.add(new Occurrence(csId, teacherId, true));
            for (int i = 0; i < periods % 2; i++)
                r.add(new Occurrence(csId, teacherId, false));
        }
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Entity conversion
    // ─────────────────────────────────────────────────────────────────────

    private List<TimetableSlotEntity> buildEntities(
            Map<DayOfWeek, List<PlacedSlot>> placed,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, DayTemplate> templates,
            Long schoolId,
            Map<Long, ClassSubjectEntity> subjectById) {

        List<TimetableSlotEntity> result = new ArrayList<>();
        int sortOrder = 1;

        for (DayOfWeek day : operatingDays) {
            DayTemplate template = templates.get(day);

            List<Long> orderedSubjects = placed.getOrDefault(day, List.of())
                    .stream()
                    .sorted(Comparator.comparingInt(PlacedSlot::position))
                    .map(PlacedSlot::classSubjectId)
                    .toList();

            int subjectQueue = 0;
            for (DaySlot ds : template.slots()) {
                if (ds.type() == DaySlotType.ACTIVITY) {
                    result.add(TimetableSlotEntity.builder()
                            .schoolId(schoolId).dayOfWeek(day)
                            .startTime(ds.start()).endTime(ds.end())
                            .slotType(SlotType.ACTIVITY)
                            .activityLabel(ds.label())
                            .sortOrder(sortOrder++).build());
                } else {
                    if (subjectQueue < orderedSubjects.size()) {
                        Long csId = orderedSubjects.get(subjectQueue++);
                        result.add(TimetableSlotEntity.builder()
                                .schoolId(schoolId).dayOfWeek(day)
                                .startTime(ds.start()).endTime(ds.end())
                                .slotType(SlotType.SUBJECT)
                                .classSubject(subjectById.get(csId))
                                .sortOrder(sortOrder++).build());
                    }
                }
            }
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private List<Occurrence> sortByConstrainedness(
            List<Occurrence> occs, List<DayOfWeek> days,
            Map<DayOfWeek, Integer> perDaySlots,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied,
            DayTemplate baseTemplate, Random rng) {

        List<Occurrence> copy = new ArrayList<>(occs);
        Collections.shuffle(copy, rng);
        copy.sort(Comparator.comparingInt(o -> {
            int needed = o.isDouble() ? 2 : 1;
            Long tid = o.teacherId();
            int legal = 0;
            for (DayOfWeek d : days) {
                if (perDaySlots.getOrDefault(d, 0) < needed) continue;
                if (tid != null) {
                    if (unavailable.getOrDefault(tid, Set.of()).contains(d)) continue;
                    if (isTeacherFullyBlockedOnDay(tid, d, needed, baseTemplate, occupied))
                        continue;
                }
                legal++;
            }
            return legal;
        }));
        return copy;
    }

    private boolean isTeacherFullyBlockedOnDay(
            Long teacherId, DayOfWeek day, int needed,
            DayTemplate template, List<OccupiedInterval> occupied) {
        int free = 0;
        for (int i = 0; i < template.subjectSlotCount(); i++) {
            if (!hasConflict(occupied, teacherId, day, template.subjectSlotTime(i)))
                free++;
            if (free >= needed) return false;
        }
        return true;
    }

    private boolean hasConflict(List<OccupiedInterval> occupied,
                                Long teacherId, DayOfWeek day, SlotTime t) {
        for (OccupiedInterval oi : occupied) {
            if (!oi.teacherId().equals(teacherId) || oi.day() != day) continue;
            if (oi.start().isBefore(t.end()) && oi.end().isAfter(t.start())) return true;
        }
        return false;
    }

    private List<OccupiedInterval> loadOccupiedIntervals(
            Long schoolId, Long excludeClassSessionId) {
        return slotRepo.findAllActiveSubjectSlotsForSchoolExcludingClass(
                        schoolId, excludeClassSessionId).stream()
                .filter(s -> s.getClassSubject() != null
                        && s.getClassSubject().getTeacherAssignment() != null)
                .map(s -> new OccupiedInterval(
                        s.getClassSubject().getTeacherAssignment().getTeacher().getId(),
                        s.getDayOfWeek(), s.getStartTime(), s.getEndTime()))
                .collect(Collectors.toList());
    }

    private void validateFeasibility(
            List<Occurrence> occs, List<DayOfWeek> days,
            Map<DayOfWeek, Integer> perDaySlots) {
        int total = occs.stream().mapToInt(o -> o.isDouble() ? 2 : 1).sum();
        int avail = perDaySlots.values().stream().mapToInt(Integer::intValue).sum();
        if (total > avail) {
            throw new BusinessRuleViolationException(
                    "Total periods (" + total + ") exceed total available slots ("
                            + avail + ") across all days. Reduce periodsPerWeek, "
                            + "extend closing time, or reduce day-specific activity durations.");
        }
    }

    private List<DayOfWeek> parseOperatingDays(List<String> raw) {
        List<DayOfWeek> result = new ArrayList<>();
        for (String d : raw) {
            try {
                result.add(DayOfWeek.valueOf(d.toUpperCase().trim()));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException("Invalid day: '" + d + "'");
            }
        }
        if (result.isEmpty()) {
            throw new BusinessRuleViolationException("At least one operating day is required.");
        }
        return result;
    }

    private Map<Long, Set<DayOfWeek>> buildUnavailabilityMap(
            List<GenerateTimetableCommand.TeacherUnavailability> specs) {
        if (specs == null) return Map.of();
        Map<Long, Set<DayOfWeek>> map = new HashMap<>();
        for (var spec : specs) {
            Set<DayOfWeek> days = new HashSet<>();
            for (String d : spec.unavailableDays()) {
                try {
                    days.add(DayOfWeek.valueOf(d.toUpperCase().trim()));
                } catch (IllegalArgumentException e) {
                    throw new BusinessRuleViolationException(
                            "Invalid day for teacher " + spec.teacherId() + ": '" + d + "'");
                }
            }
            map.put(spec.teacherId(), days);
        }
        return map;
    }

    private static Long getTeacherId(ClassSubjectEntity s) {
        if (s == null || s.getTeacherAssignment() == null
                || s.getTeacherAssignment().getTeacher() == null) return null;
        return s.getTeacherAssignment().getTeacher().getId();
    }

    private String diagnose(
            List<Occurrence> occs, Map<Long, ClassSubjectEntity> subjectById,
            List<DayOfWeek> days, Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied, DayTemplate template) {

        StringBuilder sb = new StringBuilder();
        Set<Long> seen = new HashSet<>();
        for (Occurrence o : occs) {
            if (!seen.add(o.classSubjectId())) continue;
            ClassSubjectEntity cs = subjectById.get(o.classSubjectId());
            String name = cs.getSubject() != null
                    ? cs.getSubject().getName() : "id=" + o.classSubjectId();
            Long tid = o.teacherId();
            if (tid == null) {
                sb.append("• '").append(name).append("' has no teacher assigned.\n");
                continue;
            }
            long free = days.stream()
                    .filter(d -> !unavailable.getOrDefault(tid, Set.of()).contains(d))
                    .filter(d -> !isTeacherFullyBlockedOnDay(tid, d, 1, template, occupied))
                    .count();
            if (free == 0) {
                sb.append("• Teacher of '").append(name)
                        .append("' has no available days.\n");
            }
        }
        if (sb.isEmpty()) {
            sb.append("Reduce periodsPerWeek, add more days, or vary teacher assignments.");
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Value types
    // ─────────────────────────────────────────────────────────────────────

    private record Occurrence(Long classSubjectId, Long teacherId, boolean isDouble) {}
    private record OccupiedInterval(Long teacherId, DayOfWeek day, LocalTime start, LocalTime end) {}
    private enum DaySlotType { SUBJECT, ACTIVITY }
    private record DaySlot(DaySlotType type, LocalTime start, LocalTime end, String label) {}
    private record DayTemplate(List<DaySlot> slots, int subjectSlotCount) {
        SlotTime subjectSlotTime(int idx) {
            int count = 0;
            for (DaySlot s : slots) {
                if (s.type() == DaySlotType.SUBJECT) {
                    if (count == idx) return new SlotTime(s.start(), s.end());
                    count++;
                }
            }
            throw new IllegalArgumentException("slotIdx " + idx + " out of range");
        }
    }
    private record SlotTime(LocalTime start, LocalTime end) {}
    private record PlacedSlot(Long classSubjectId, int position, boolean isDouble) {}
}