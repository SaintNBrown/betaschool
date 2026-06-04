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

/**
 * Two-phase timetable generator with canonical clock-time pinning.
 *
 * ── CORE DESIGN PRINCIPLE ────────────────────────────────────────────────
 *
 * Global activities (onlyOnDays null/empty) are pinned to the SAME clock
 * time on EVERY operating day. Their position is computed from the canonical
 * day (no day-specific activities). Day-specific activities on any given day
 * never shift the clock time of global activities on that day.
 *
 * Day-specific activities (onlyOnDays populated) replace subject slots on
 * their days. Their duration is expressed in SUBJECT SLOT PERIODS
 * (periodsReplaced), not minutes. This makes the request intent explicit:
 * "Sports replaces 1 period" rather than "Sports is 40 minutes."
 *
 * ── TEMPLATE ARCHITECTURE ────────────────────────────────────────────────
 *
 * Step 1 — CANONICAL SLOT TIMES:
 *   Compute the clock start/end of every subject slot position using ONLY
 *   global activities. This produces one canonical timeline shared across
 *   all days. Break at 11:30am is always 11:30am regardless of the day.
 *
 * Step 2 — PER-DAY TEMPLATES:
 *   For each day, take the canonical timeline and:
 *   a) Insert global activities at their canonical clock positions (unchanged).
 *   b) Replace subject slots consumed by day-specific activities with those
 *      activity blocks at the same clock positions as the displaced slots.
 *   c) Place isLastOfDay activities after all subject slots.
 *
 * ── OCCURRENCE STRUCTURE ─────────────────────────────────────────────────
 *   periods=1  → 1 single
 *   periods=2  → 2 singles different days (default) or 1 double (forceDouble)
 *   periods≥3  → max doubles + singles, each occurrence on distinct day
 *
 * ── PHASES ───────────────────────────────────────────────────────────────
 *   Phase 1: assign each occurrence to a day (backtracking CSP)
 *   Phase 2: place occurrences into slot positions within each day
 *   Post:    end-time balancing (≤2 slot gap across days)
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Transactional
public class GenerateTimetableHandler
        implements CommandHandler<GenerateTimetableCommand, Long> {

    private static final int MAX_PHASE1 = 100;
    private static final int MAX_PHASE2 =  50;

    private final JpaClassSessionRepository  classSessionRepo;
    private final JpaClassSubjectRepository  classSubjectRepo;
    private final JpaTimetableSlotRepository slotRepo;
    private final JpaTimetableRepository     timetableRepo;

    // ═════════════════════════════════════════════════════════════════════
    //  Entry point
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public Long handle(GenerateTimetableCommand cmd) {
        Long schoolId    = SchoolIdInjector.require();
        Long currentUser = TenantContext.getUserId();

        ClassSessionEntity classSession =
                classSessionRepo.findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "ClassSession", cmd.classSessionId()));

        List<DayOfWeek> operatingDays = parseOperatingDays(cmd.operatingDays());
        Map<Long, Set<DayOfWeek>> unavailable = buildUnavailabilityMap(cmd.teacherUnavailableDays());

        List<ClassSubjectEntity> classSubjects =
                classSubjectRepo.findByClassSessionIdAndSchoolId(cmd.classSessionId(), schoolId);
        Map<Long, ClassSubjectEntity> subjectById = classSubjects.stream()
                .collect(Collectors.toMap(ClassSubjectEntity::getId, s -> s));

        List<Occurrence> occurrences =
                buildOccurrences(cmd.subjectFrequencies(), subjectById, operatingDays.size());

        int slotMins = cmd.slotDurationMinutes();

        // ── Step 1: canonical slot times (global activities only) ─────────
        // These times are pinned and identical on every operating day.
        List<CanonicalSlot> canonical = buildCanonicalSlots(cmd, slotMins);
        int slotsPerDay = (int) canonical.stream()
                .filter(s -> s.type() == SlotKind.SUBJECT).count();

        // ── Step 2: per-day subject slot capacity ─────────────────────────
        // Global activities don't reduce capacity; day-specific ones do.
        Map<DayOfWeek, Integer> perDaySlots = computePerDaySlots(
                cmd, operatingDays, canonical, slotMins);

        // ── Step 3: apply isLastOfDay cap across all days ─────────────────
        perDaySlots = applyLastOfDayCap(cmd, operatingDays, perDaySlots, canonical, slotMins);

        // ── Step 4: build per-day templates from canonical baseline ───────
        Map<DayOfWeek, DayTemplate> templates =
                buildDayTemplates(cmd, operatingDays, canonical, perDaySlots, slotMins);

        validateFeasibility(occurrences, operatingDays, perDaySlots);

        List<OccupiedInterval> occupied = loadOccupiedIntervals(schoolId, cmd.classSessionId());
        DayTemplate baseTemplate = templates.get(operatingDays.get(0));

        // ── Phase 1: day assignment ───────────────────────────────────────
        Map<DayOfWeek, List<Occurrence>> dayAssignment = null;
        Random rng = new Random();
        for (int a = 0; a < MAX_PHASE1 && dayAssignment == null; a++) {
            dayAssignment = phase1DayAssign(occurrences, operatingDays, perDaySlots,
                    unavailable, occupied, baseTemplate, rng);
        }
        if (dayAssignment == null) {
            int minSlots = perDaySlots.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            throw new BusinessRuleViolationException(
                    "Could not assign subjects to days after " + MAX_PHASE1 + " attempts.\n"
                            + diagnose(occurrences, subjectById, operatingDays,
                            unavailable, occupied, baseTemplate, minSlots));
        }

        // ── Phase 2: slot placement ───────────────────────────────────────
        Map<DayOfWeek, List<PlacedSlot>> placed = null;
        for (int a = 0; a < MAX_PHASE2 && placed == null; a++) {
            placed = phase2SlotPlace(dayAssignment, operatingDays, templates,
                    subjectById, occupied, rng);
        }
        if (placed == null)
            throw new BusinessRuleViolationException(
                    "Could not arrange slots within days after " + MAX_PHASE2 + " attempts.");

        placed = balanceEndTimes(placed, operatingDays, perDaySlots);

        // ── Persist ───────────────────────────────────────────────────────
        timetableRepo.deactivateAllForClassSession(cmd.classSessionId(), schoolId);
        int nextVersion = timetableRepo
                .findMaxVersionByClassSessionIdAndSchoolId(cmd.classSessionId(), schoolId) + 1;
        String notes = (cmd.notes() != null && !cmd.notes().isBlank())
                ? cmd.notes() : "Auto-generated v" + nextVersion;

        TimetableEntity timetable = timetableRepo.save(TimetableEntity.builder()
                .schoolId(schoolId).classSession(classSession)
                .version(nextVersion).active(true).notes(notes)
                .createdBy(currentUser).build());

        List<TimetableSlotEntity> entities =
                buildEntities(placed, operatingDays, templates, schoolId, subjectById);
        entities.forEach(s -> s.setTimetable(timetable));
        slotRepo.saveAll(entities);

        log.info("Generated timetable id={} v={} classSession={} school={}",
                timetable.getId(), nextVersion, cmd.classSessionId(), schoolId);
        return timetable.getId();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Step 1 — Canonical slot timeline (global activities only)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Builds the canonical ordered list of slots using ONLY global activities
     * (isDaySpecific=false). Subject slot positions get their definitive clock
     * times here. These times are reused verbatim for every operating day.
     *
     * slotsPerDay is derived from schoolClosingTime (if provided) or from
     * total occurrences. Global activities consume clock time but do NOT
     * reduce slotsPerDay — they push the day's end time later.
     */
    private List<CanonicalSlot> buildCanonicalSlots(
            GenerateTimetableCommand cmd, int slotMins) {

        List<GenerateTimetableCommand.ActivitySpec> globals = (cmd.activities() == null)
                ? List.of()
                : cmd.activities().stream()
                .filter(a -> !a.isDaySpecific() && !a.isLast())
                .sorted(Comparator.comparingInt(
                        GenerateTimetableCommand.ActivitySpec::afterSlotNumber))
                .collect(Collectors.toList());

        List<GenerateTimetableCommand.ActivitySpec> globalLast = (cmd.activities() == null)
                ? List.of()
                : cmd.activities().stream()
                .filter(a -> !a.isDaySpecific() && a.isLast())
                .collect(Collectors.toList());

        // Determine how many subject slots the canonical day has
        int globalActivityMins = globals.stream()
                .mapToInt(a -> a.resolvedMinutes(slotMins)).sum()
                + globalLast.stream()
                .mapToInt(a -> a.resolvedMinutes(slotMins)).sum();

        int slotsPerDay;
        if (cmd.schoolClosingTime() != null) {
            long totalMins = java.time.Duration.between(
                    cmd.schoolStartTime(), cmd.schoolClosingTime()).toMinutes();
            if (totalMins <= 0) throw new BusinessRuleViolationException(
                    "schoolClosingTime must be after schoolStartTime.");
            slotsPerDay = (int) Math.max(1, (totalMins - globalActivityMins) / slotMins);
        } else {
            // Will be refined later in computePerDaySlots; use generous initial estimate
            slotsPerDay = 12; // max cap — trimmed in computePerDaySlots
        }

        // Build canonical slot list
        List<CanonicalSlot> result = new ArrayList<>();
        LocalTime cursor = cmd.schoolStartTime();
        int actIdx = 0;

        // Global activities before all subjects (afterSlotNumber == 0)
        while (actIdx < globals.size() && globals.get(actIdx).afterSlotNumber() == 0) {
            var a = globals.get(actIdx++);
            LocalTime end = cursor.plusMinutes(a.resolvedMinutes(slotMins));
            result.add(new CanonicalSlot(SlotKind.GLOBAL_ACTIVITY, cursor, end, a.label(), -1));
            cursor = end;
        }

        for (int s = 1; s <= slotsPerDay; s++) {
            LocalTime end = cursor.plusMinutes(slotMins);
            result.add(new CanonicalSlot(SlotKind.SUBJECT, cursor, end, null, s - 1));
            cursor = end;
            while (actIdx < globals.size()
                    && globals.get(actIdx).afterSlotNumber() == s) {
                var a = globals.get(actIdx++);
                LocalTime actEnd = cursor.plusMinutes(a.resolvedMinutes(slotMins));
                result.add(new CanonicalSlot(SlotKind.GLOBAL_ACTIVITY, cursor, actEnd, a.label(), -1));
                cursor = actEnd;
            }
        }
        // Remaining positioned globals beyond slotsPerDay
        while (actIdx < globals.size()) {
            var a = globals.get(actIdx++);
            LocalTime end = cursor.plusMinutes(a.resolvedMinutes(slotMins));
            result.add(new CanonicalSlot(SlotKind.GLOBAL_ACTIVITY, cursor, end, a.label(), -1));
            cursor = end;
        }
        for (var a : globalLast) {
            LocalTime end = cursor.plusMinutes(a.resolvedMinutes(slotMins));
            result.add(new CanonicalSlot(SlotKind.GLOBAL_LAST_ACTIVITY, cursor, end, a.label(), -1));
            cursor = end;
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Step 2 — Per-day subject slot capacity
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Derives how many subject slots each day has.
     *
     * The canonical day has slotsPerDay subject slots.
     * Day-specific activities replace some of those slots:
     *   replacedSlots = sum of activity.slotsReplaced() for activities on that day
     *   (not counting isLastOfDay — they go AFTER all slots)
     *
     * isLastOfDay day-specific activities do NOT reduce slotsPerDay;
     * they sit after the last subject slot.
     */
    private Map<DayOfWeek, Integer> computePerDaySlots(
            GenerateTimetableCommand cmd,
            List<DayOfWeek> operatingDays,
            List<CanonicalSlot> canonical,
            int slotMins) {

        int canonicalSubjectSlots = (int) canonical.stream()
                .filter(s -> s.type() == SlotKind.SUBJECT).count();

        // If no closingTime, derive from occurrences
        int baseSlots;
        if (cmd.schoolClosingTime() != null) {
            baseSlots = canonicalSubjectSlots;
        } else {
            // We need actual occurrence count — re-read subjectFrequencies
            int totalPeriods = cmd.subjectFrequencies().stream()
                    .mapToInt(GenerateTimetableCommand.SubjectFrequency::periodsPerWeek)
                    .sum();
            int numDays = operatingDays.size();
            baseSlots = Math.min((int) Math.ceil((double) totalPeriods / numDays) + 2, 12);
        }

        List<GenerateTimetableCommand.ActivitySpec> activities =
                cmd.activities() == null ? List.of() : cmd.activities();

        Map<DayOfWeek, Integer> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            int replaced = activities.stream()
                    .filter(a -> a.isDaySpecific() && !a.isLast() && appliesToDay(a, day))
                    .mapToInt(GenerateTimetableCommand.ActivitySpec::slotsReplaced)
                    .sum();
            result.put(day, Math.max(baseSlots - replaced, 0));
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Step 3 — isLastOfDay cross-day cap
    // ═════════════════════════════════════════════════════════════════════

    /**
     * If any isLastOfDay activity exists, no subject slot on ANY day
     * should end later than the earliest clock time that activity begins
     * across all its days.
     *
     * For a day-specific isLastOfDay activity: its start time on day D is
     *   startTime + globalMins + daySpecificNonLastMins_for_D
     *   + perDaySlots[D] * slotMins
     * That start time becomes the latest any subject can end on all days.
     *
     * For a global isLastOfDay activity: it begins at the canonical last
     * subject slot's end time, which is already the cap for all days.
     */
    private Map<DayOfWeek, Integer> applyLastOfDayCap(
            GenerateTimetableCommand cmd,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> perDaySlots,
            List<CanonicalSlot> canonical,
            int slotMins) {

        if (cmd.activities() == null) return perDaySlots;

        List<GenerateTimetableCommand.ActivitySpec> lastActivities = cmd.activities().stream()
                .filter(GenerateTimetableCommand.ActivitySpec::isLast)
                .collect(Collectors.toList());
        if (lastActivities.isEmpty()) return perDaySlots;

        // Global activity minutes before all subjects (clock offset for any day)
        int globalMins = cmd.activities().stream()
                .filter(a -> !a.isDaySpecific() && !a.isLast())
                .mapToInt(a -> a.resolvedMinutes(slotMins))
                .sum();

        // Find the canonical end time of the last subject slot
        // (= the latest a subject can end on a full day)
        LocalTime latestSubjectEnd = canonical.stream()
                .filter(s -> s.type() == SlotKind.SUBJECT)
                .map(CanonicalSlot::end)
                .max(LocalTime::compareTo)
                .orElse(cmd.schoolStartTime());

        // For each isLastOfDay activity, find its start time on each of its days
        LocalTime globalCutoff = null;
        for (var act : lastActivities) {
            List<DayOfWeek> actDays = act.isDaySpecific()
                    ? operatingDays.stream().filter(d -> appliesToDay(act, d))
                    .collect(Collectors.toList())
                    : operatingDays; // global last activity applies to all days

            for (DayOfWeek day : actDays) {
                // Day-specific non-last minutes on this day
                int dsNonLastMins = cmd.activities().stream()
                        .filter(a -> a.isDaySpecific() && !a.isLast() && appliesToDay(a, day))
                        .mapToInt(a -> a.slotsReplaced() * slotMins)
                        .sum();

                int slots = perDaySlots.getOrDefault(day, 0);
                long minutesUsed = (long) globalMins + dsNonLastMins + (long) slots * slotMins;
                LocalTime actStart = cmd.schoolStartTime().plusMinutes(minutesUsed);

                if (globalCutoff == null || actStart.isBefore(globalCutoff))
                    globalCutoff = actStart;
            }
        }

        if (globalCutoff == null) return perDaySlots;

        // Re-cap every day: subjects must end by globalCutoff
        Map<DayOfWeek, Integer> capped = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            int dsNonLastMins = cmd.activities().stream()
                    .filter(a -> a.isDaySpecific() && !a.isLast() && appliesToDay(a, day))
                    .mapToInt(a -> a.slotsReplaced() * slotMins)
                    .sum();
            // How many subject slots fit before globalCutoff on this day?
            long availMins = java.time.Duration.between(
                    cmd.schoolStartTime(), globalCutoff).toMinutes()
                    - globalMins - dsNonLastMins;
            int maxSlots = availMins <= 0 ? 0 : (int) (availMins / slotMins);
            capped.put(day, Math.min(perDaySlots.getOrDefault(day, 0), maxSlots));
        }
        return capped;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Step 4 — Per-day templates from canonical baseline
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Builds the DayTemplate for each operating day.
     *
     * The canonical slot list defines clock times for all subject positions
     * and global activities. For each day:
     *   - Global activity slots are kept at exactly their canonical times.
     *   - Day-specific non-last activities replace the subject slots they
     *     consume, at those slots' canonical start times.
     *   - Subject slots not consumed by day-specific activities remain.
     *   - isLastOfDay activities are appended at the end.
     *
     * This guarantees Break is at 11:30am on EVERY day regardless of what
     * day-specific activities exist on a given day.
     */
    private Map<DayOfWeek, DayTemplate> buildDayTemplates(
            GenerateTimetableCommand cmd,
            List<DayOfWeek> operatingDays,
            List<CanonicalSlot> canonical,
            Map<DayOfWeek, Integer> perDaySlots,
            int slotMins) {

        List<GenerateTimetableCommand.ActivitySpec> activities =
                cmd.activities() == null ? List.of() : cmd.activities();

        Map<DayOfWeek, DayTemplate> result = new LinkedHashMap<>();

        for (DayOfWeek day : operatingDays) {
            int daySubjectSlots = perDaySlots.getOrDefault(day, 0);

            // Day-specific non-last activities for this day, sorted by afterSlotNumber
            List<GenerateTimetableCommand.ActivitySpec> dsActivities = activities.stream()
                    .filter(a -> a.isDaySpecific() && !a.isLast() && appliesToDay(a, day))
                    .sorted(Comparator.comparingInt(
                            GenerateTimetableCommand.ActivitySpec::afterSlotNumber))
                    .collect(Collectors.toList());

            // Build a set of subject-slot indices consumed by day-specific activities.
            // Activity with afterSlotNumber=N and periodsReplaced=P consumes slot indices N..N+P-1
            // (0-indexed from the start of the day's subject slots).
            // But we need to think carefully: afterSlotNumber means "replaces starting at slot N".
            // Slots are 0-indexed in our canonical list.
            Set<Integer> consumedSlots = new LinkedHashSet<>();
            for (var act : dsActivities) {
                int start = act.afterSlotNumber(); // 0-indexed slot position
                int slots = act.slotsReplaced();
                for (int i = start; i < start + slots; i++) consumedSlots.add(i);
            }

            // Walk canonical list, build the day's DaySlot list
            List<DaySlot> daySlots = new ArrayList<>();
            int subjectIdxInDay = 0;   // counts subject slots seen in canonical
            int filledSubjects = 0;    // counts subject slots actually emitted for this day

            // Map from consumed-slot-start → activity label+end for insertion
            // Build: for each ds activity, map its afterSlotNumber → (label, minutesTotal)
            Map<Integer, List<GenerateTimetableCommand.ActivitySpec>> actsBySlot = new LinkedHashMap<>();
            for (var act : dsActivities) {
                actsBySlot.computeIfAbsent(act.afterSlotNumber(), k -> new ArrayList<>()).add(act);
            }

            for (CanonicalSlot cs : canonical) {
                if (cs.type() == SlotKind.GLOBAL_ACTIVITY
                        || cs.type() == SlotKind.GLOBAL_LAST_ACTIVITY) {
                    // Global activity: emit at canonical clock time, always
                    daySlots.add(new DaySlot(DaySlotType.ACTIVITY, cs.start(), cs.end(), cs.label()));
                } else {
                    // SUBJECT slot in canonical
                    int idx = subjectIdxInDay;

                    // Check if a day-specific activity starts at this slot position
                    List<GenerateTimetableCommand.ActivitySpec> actsHere = actsBySlot.get(idx);
                    if (actsHere != null) {
                        // Insert day-specific activity(ies) starting at canonical start of this slot
                        LocalTime actCursor = cs.start();
                        for (var act : actsHere) {
                            int actMins = act.slotsReplaced() * slotMins;
                            LocalTime actEnd = actCursor.plusMinutes(actMins);
                            daySlots.add(new DaySlot(DaySlotType.ACTIVITY, actCursor, actEnd, act.label()));
                            actCursor = actEnd;
                        }
                        // Skip the consumed canonical subject slots
                        int skip = actsHere.stream()
                                .mapToInt(GenerateTimetableCommand.ActivitySpec::slotsReplaced).sum();
                        // Advance subjectIdxInDay past all consumed slots
                        subjectIdxInDay += skip;
                        // Also skip the remaining canonical subject entries for these slots
                        // (handled by the subjectIdxInDay check below for subsequent canonicals)
                    } else if (!consumedSlots.contains(idx) && filledSubjects < daySubjectSlots) {
                        // Regular subject slot for this day
                        daySlots.add(new DaySlot(DaySlotType.SUBJECT, cs.start(), cs.end(), null));
                        filledSubjects++;
                        subjectIdxInDay++;
                    } else if (consumedSlots.contains(idx)) {
                        // This canonical slot was consumed by a preceding multi-slot activity
                        subjectIdxInDay++;
                    } else {
                        // Surplus slot (filledSubjects >= daySubjectSlots) — skip
                        subjectIdxInDay++;
                    }
                }
            }

            // Append isLastOfDay day-specific activities
            LocalTime lastEnd = daySlots.isEmpty()
                    ? cmd.schoolStartTime()
                    : daySlots.get(daySlots.size() - 1).end();
            for (var act : activities.stream()
                    .filter(a -> a.isDaySpecific() && a.isLast() && appliesToDay(a, day))
                    .sorted(Comparator.comparingInt(
                            GenerateTimetableCommand.ActivitySpec::afterSlotNumber))
                    .toList()) {
                LocalTime end = lastEnd.plusMinutes(act.slotsReplaced() * slotMins);
                daySlots.add(new DaySlot(DaySlotType.ACTIVITY, lastEnd, end, act.label()));
                lastEnd = end;
            }

            result.put(day, new DayTemplate(daySlots, daySubjectSlots));
        }
        return result;
    }

    private boolean appliesToDay(GenerateTimetableCommand.ActivitySpec spec, DayOfWeek day) {
        if (!spec.isDaySpecific()) return true;
        for (String d : spec.onlyOnDays()) {
            try { if (DayOfWeek.valueOf(d.toUpperCase().trim()) == day) return true; }
            catch (IllegalArgumentException ignored) {}
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Phase 1 — Day assignment (backtracking CSP)
    // ═════════════════════════════════════════════════════════════════════

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
                occurrences, operatingDays, perDaySlots, unavailable, occupied, baseTemplate, rng);

        return p1Backtrack(ordered, 0, result, freeSlots, subjectDays,
                operatingDays, unavailable, occupied, baseTemplate);
    }

    private Map<DayOfWeek, List<Occurrence>> p1Backtrack(
            List<Occurrence> occs, int idx,
            Map<DayOfWeek, List<Occurrence>> assignment,
            Map<DayOfWeek, Integer> freeSlots,
            Map<Long, Set<DayOfWeek>> subjectDays,
            List<DayOfWeek> operatingDays,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied,
            DayTemplate baseTemplate) {

        if (idx == occs.size()) return assignment;
        Occurrence occ = occs.get(idx);
        int needed = occ.isDouble() ? 2 : 1;
        Long teacherId = occ.teacherId();

        List<DayOfWeek> shuffled = new ArrayList<>(operatingDays);
        Collections.shuffle(shuffled);

        for (DayOfWeek day : shuffled) {
            if (subjectDays.getOrDefault(occ.classSubjectId(), Set.of()).contains(day)) continue;
            if (teacherId != null
                    && unavailable.getOrDefault(teacherId, Set.of()).contains(day)) continue;
            if (freeSlots.getOrDefault(day, 0) < needed) continue;
            if (teacherId != null
                    && isTeacherFullyBlockedOnDay(teacherId, day, needed, baseTemplate, occupied))
                continue;

            assignment.get(day).add(occ);
            freeSlots.merge(day, -needed, Integer::sum);
            subjectDays.computeIfAbsent(occ.classSubjectId(), k -> new HashSet<>()).add(day);

            var sub = p1Backtrack(occs, idx + 1, assignment, freeSlots, subjectDays,
                    operatingDays, unavailable, occupied, baseTemplate);
            if (sub != null) return sub;

            assignment.get(day).remove(occ);
            freeSlots.merge(day, needed, Integer::sum);
            subjectDays.get(occ.classSubjectId()).remove(day);
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Phase 2 — Slot placement within each day
    // ═════════════════════════════════════════════════════════════════════

    private Map<DayOfWeek, List<PlacedSlot>> phase2SlotPlace(
            Map<DayOfWeek, List<Occurrence>> dayAssignment,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, DayTemplate> templates,
            Map<Long, ClassSubjectEntity> subjectById,
            List<OccupiedInterval> occupied, Random rng) {

        Map<DayOfWeek, List<PlacedSlot>> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            List<Occurrence> occs = dayAssignment.getOrDefault(day, List.of());
            if (occs.isEmpty()) { result.put(day, List.of()); continue; }
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
        Long[]    slotAssign = new Long[n];
        boolean[] isDouble   = new boolean[n];

        Set<Integer> actBoundaries = activityBoundarySet(template);

        List<Occurrence> doubles = occs.stream().filter(Occurrence::isDouble)
                .collect(Collectors.toList());
        List<Occurrence> singles = occs.stream().filter(o -> !o.isDouble())
                .collect(Collectors.toList());
        Collections.shuffle(doubles, rng);
        Collections.shuffle(singles, rng);

        for (Occurrence d : doubles) {
            Long tid = d.teacherId();
            List<Integer> candidates = buildDoubleCandidates(
                    n, slotAssign, actBoundaries, tid, day, template, occupied);
            if (candidates.isEmpty()) return null;
            int pos = candidates.get(0);
            slotAssign[pos] = slotAssign[pos + 1] = d.classSubjectId();
            isDouble[pos] = isDouble[pos + 1] = true;
        }

        List<Integer> freePos = new ArrayList<>();
        for (int i = 0; i < n; i++) if (slotAssign[i] == null) freePos.add(i);
        Collections.shuffle(freePos, rng);

        for (Occurrence s : singles) {
            boolean placed = false;
            Long tid = s.teacherId();
            for (int pos : freePos) {
                if (slotAssign[pos] != null) continue;
                if (tid != null && hasConflict(occupied, tid, day, template.subjectSlotTime(pos)))
                    continue;
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
            if (ds.type() == DaySlotType.SUBJECT) subIdx++;
            else if (subIdx >= 0)                  boundaries.add(subIdx);
        }
        return boundaries;
    }

    private List<Integer> buildDoubleCandidates(
            int n, Long[] slotAssign, Set<Integer> actBoundaries,
            Long teacherId, DayOfWeek day, DayTemplate template,
            List<OccupiedInterval> occupied) {

        List<Integer> preferred = new ArrayList<>();
        List<Integer> normal    = new ArrayList<>();

        for (int pos = 0; pos < n - 1; pos++) {
            if (slotAssign[pos] != null || slotAssign[pos + 1] != null) continue;
            if (actBoundaries.contains(pos)) continue;
            if (teacherId != null) {
                if (hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos))
                        || hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos + 1)))
                    continue;
            }
            if (actBoundaries.contains(pos + 1)) preferred.add(pos);
            else                                  normal.add(pos);
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
                    Long tmp  = slots[i + 1];    slots[i + 1]    = slots[i + 2];    slots[i + 2]    = tmp;
                    boolean t = isDouble[i + 1]; isDouble[i + 1] = isDouble[i + 2]; isDouble[i + 2] = t;
                    improved = true;
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  End-time balancing
    // ═════════════════════════════════════════════════════════════════════

    private Map<DayOfWeek, List<PlacedSlot>> balanceEndTimes(
            Map<DayOfWeek, List<PlacedSlot>> placed,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> perDaySlots) {

        Map<DayOfWeek, Integer> lastIdx = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays)
            lastIdx.put(day, placed.getOrDefault(day, List.of()).stream()
                    .mapToInt(PlacedSlot::position).max().orElse(-1));

        int maxLast = lastIdx.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        Map<DayOfWeek, List<PlacedSlot>> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            int last   = lastIdx.getOrDefault(day, -1);
            int gap    = maxLast - last;
            int dayMax = perDaySlots.getOrDefault(day, maxLast + 1) - 1;

            if (gap <= 2 || last < 0) { result.put(day, placed.getOrDefault(day, List.of())); continue; }

            int shift = Math.min(gap - 2, dayMax - last);
            if (shift <= 0) { result.put(day, placed.getOrDefault(day, List.of())); continue; }

            Set<Integer> used = new HashSet<>();
            List<PlacedSlot> shifted = new ArrayList<>();
            for (PlacedSlot ps : placed.get(day)) {
                int pos = Math.min(ps.position() + shift, dayMax);
                while (used.contains(pos) && pos > 0) pos--;
                used.add(pos);
                shifted.add(new PlacedSlot(ps.classSubjectId(), pos, ps.isDouble()));
            }
            result.put(day, shifted);
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Entity conversion — compact packing (no blank gaps)
    // ═════════════════════════════════════════════════════════════════════

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

            // Sort placed slots by position and queue them
            List<Long> subjectQueue = placed.getOrDefault(day, List.of()).stream()
                    .sorted(Comparator.comparingInt(PlacedSlot::position))
                    .map(PlacedSlot::classSubjectId)
                    .collect(Collectors.toList());

            int qIdx = 0;
            for (DaySlot ds : template.slots()) {
                if (ds.type() == DaySlotType.ACTIVITY) {
                    result.add(TimetableSlotEntity.builder()
                            .schoolId(schoolId).dayOfWeek(day)
                            .startTime(ds.start()).endTime(ds.end())
                            .slotType(SlotType.ACTIVITY).activityLabel(ds.label())
                            .sortOrder(sortOrder++).build());
                } else {
                    // SUBJECT slot — emit from queue; skip if exhausted (surplus slot)
                    if (qIdx < subjectQueue.size()) {
                        Long csId = subjectQueue.get(qIdx++);
                        result.add(TimetableSlotEntity.builder()
                                .schoolId(schoolId).dayOfWeek(day)
                                .startTime(ds.start()).endTime(ds.end())
                                .slotType(SlotType.SUBJECT)
                                .classSubject(subjectById.get(csId))
                                .sortOrder(sortOrder++).build());
                    }
                    // Surplus: no entity emitted, no blank gap in output
                }
            }
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Occurrence building
    // ═════════════════════════════════════════════════════════════════════

    private List<Occurrence> buildOccurrences(
            List<GenerateTimetableCommand.SubjectFrequency> frequencies,
            Map<Long, ClassSubjectEntity> subjectById, int numDays) {

        List<Occurrence> all = new ArrayList<>();
        Set<Long> covered = new HashSet<>();

        for (var freq : frequencies) {
            Long csId = freq.classSubjectId();
            if (!subjectById.containsKey(csId))
                throw new BusinessRuleViolationException(
                        "ClassSubject id=" + csId + " not in this class-session.");
            int periods = freq.periodsPerWeek();
            if (periods < 1)
                throw new BusinessRuleViolationException(
                        "periodsPerWeek ≥ 1 required for classSubjectId=" + csId);

            boolean forceDouble = Boolean.TRUE.equals(freq.forceDouble());
            List<Occurrence> occs = deriveOccurrences(
                    csId, getTeacherId(subjectById.get(csId)), periods, forceDouble);

            if (occs.size() > numDays)
                throw new BusinessRuleViolationException(
                        "Subject id=" + csId + " needs " + occs.size()
                                + " occurrences but only " + numDays + " days available.");

            all.addAll(occs);
            covered.add(csId);
        }

        List<String> omitted = subjectById.values().stream()
                .filter(cs -> !covered.contains(cs.getId()))
                .map(cs -> "'" + (cs.getSubject() != null ? cs.getSubject().getName()
                        : "id=" + cs.getId()) + "' (classSubjectId=" + cs.getId() + ")")
                .collect(Collectors.toList());
        if (!omitted.isEmpty())
            throw new BusinessRuleViolationException(
                    "Missing from subjectFrequencies: " + String.join(", ", omitted));

        return all;
    }

    private List<Occurrence> deriveOccurrences(
            Long csId, Long teacherId, int periods, boolean forceDouble) {
        List<Occurrence> r = new ArrayList<>();
        if (periods == 1) {
            r.add(new Occurrence(csId, teacherId, false));
        } else if (periods == 2) {
            if (forceDouble) r.add(new Occurrence(csId, teacherId, true));
            else { r.add(new Occurrence(csId, teacherId, false)); r.add(new Occurrence(csId, teacherId, false)); }
        } else {
            for (int i = 0; i < periods / 2; i++) r.add(new Occurrence(csId, teacherId, true));
            for (int i = 0; i < periods % 2; i++) r.add(new Occurrence(csId, teacherId, false));
        }
        return r;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Utilities
    // ═════════════════════════════════════════════════════════════════════

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
                    if (isTeacherFullyBlockedOnDay(tid, d, needed, baseTemplate, occupied)) continue;
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
            if (!hasConflict(occupied, teacherId, day, template.subjectSlotTime(i))) free++;
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

    private List<OccupiedInterval> loadOccupiedIntervals(Long schoolId, Long excludeCs) {
        return slotRepo.findAllActiveSubjectSlotsForSchoolExcludingClass(schoolId, excludeCs)
                .stream()
                .filter(s -> s.getClassSubject() != null
                        && s.getClassSubject().getTeacherAssignment() != null)
                .map(s -> new OccupiedInterval(
                        s.getClassSubject().getTeacherAssignment().getTeacher().getId(),
                        s.getDayOfWeek(), s.getStartTime(), s.getEndTime()))
                .collect(Collectors.toList());
    }

    private void validateFeasibility(List<Occurrence> occs,
                                     List<DayOfWeek> days,
                                     Map<DayOfWeek, Integer> perDaySlots) {
        int total = occs.stream().mapToInt(o -> o.isDouble() ? 2 : 1).sum();
        int avail = perDaySlots.values().stream().mapToInt(Integer::intValue).sum();
        if (total > avail)
            throw new BusinessRuleViolationException(
                    "Total periods (" + total + ") exceed available slots (" + avail
                            + "). Reduce periodsPerWeek or day-specific activity periods.");
    }

    private List<DayOfWeek> parseOperatingDays(List<String> raw) {
        List<DayOfWeek> result = new ArrayList<>();
        for (String d : raw) {
            try { result.add(DayOfWeek.valueOf(d.toUpperCase().trim())); }
            catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException("Invalid day: '" + d + "'");
            }
        }
        if (result.isEmpty())
            throw new BusinessRuleViolationException("At least one operating day is required.");
        return result;
    }

    private Map<Long, Set<DayOfWeek>> buildUnavailabilityMap(
            List<GenerateTimetableCommand.TeacherUnavailability> specs) {
        if (specs == null) return Map.of();
        Map<Long, Set<DayOfWeek>> map = new HashMap<>();
        for (var spec : specs) {
            Set<DayOfWeek> days = new HashSet<>();
            for (String d : spec.unavailableDays()) {
                try { days.add(DayOfWeek.valueOf(d.toUpperCase().trim())); }
                catch (IllegalArgumentException e) {
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

    private String diagnose(List<Occurrence> occs, Map<Long, ClassSubjectEntity> subjectById,
                            List<DayOfWeek> days, Map<Long, Set<DayOfWeek>> unavailable,
                            List<OccupiedInterval> occupied, DayTemplate template, int slotsPerDay) {
        StringBuilder sb = new StringBuilder();
        Set<Long> seen = new HashSet<>();
        for (Occurrence o : occs) {
            if (!seen.add(o.classSubjectId())) continue;
            ClassSubjectEntity cs = subjectById.get(o.classSubjectId());
            String name = cs.getSubject() != null ? cs.getSubject().getName() : "id=" + o.classSubjectId();
            Long tid = o.teacherId();
            if (tid == null) { sb.append("• '").append(name).append("' has no teacher.\n"); continue; }
            long free = days.stream()
                    .filter(d -> !unavailable.getOrDefault(tid, Set.of()).contains(d))
                    .filter(d -> !isTeacherFullyBlockedOnDay(tid, d, 1, template, occupied))
                    .count();
            if (free == 0)
                sb.append("• Teacher of '").append(name).append("' has no available days.\n");
        }
        if (sb.isEmpty())
            sb.append("Reduce periodsPerWeek, add more days, or vary teacher assignments.");
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Value types
    // ═════════════════════════════════════════════════════════════════════

    private record Occurrence(Long classSubjectId, Long teacherId, boolean isDouble) {}
    private record OccupiedInterval(Long teacherId, DayOfWeek day, LocalTime start, LocalTime end) {}
    private enum SlotKind        { SUBJECT, GLOBAL_ACTIVITY, GLOBAL_LAST_ACTIVITY }
    private enum DaySlotType     { SUBJECT, ACTIVITY }
    private record CanonicalSlot(SlotKind type, LocalTime start, LocalTime end, String label, int subjectIdx) {}
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