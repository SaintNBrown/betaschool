package com.betaschool;

import com.betaschool.command.model.AcademicCommand.*;
import com.betaschool.command.model.ExaminationCommand.CreateExaminationCommand;
import com.betaschool.command.model.ExaminationCommand.RecordResultCommand;
import com.betaschool.command.model.StudentCommand.CreateStudentCommand;
import com.betaschool.command.model.StudentCommand.EnrollStudentInClassCommand;
import com.betaschool.command.model.StudentCommand.EnrollStudentInSubjectCommand;
import com.betaschool.command.model.TeacherCommand.AssignTeacherToClassCommand;
import com.betaschool.command.model.TeacherCommand.AssignTeacherToSubjectCommand;
import com.betaschool.command.model.TeacherCommand.CreateTeacherCommand;
import com.betaschool.query.model.StudentQuery.GetStudentReportCardQuery;
import com.betaschool.query.model.StudentQueryResult.ReportCard;
import com.betaschool.query.model.StudentQueryResult.ReportCardEntry;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BetaSchoolIntegrationTest {

    @Autowired CommandBus commandBus;
    @Autowired QueryBus queryBus;

    @Test
    void fullSchoolFlowProducesCorrectReportCard() {
        // 1. Create session
        Long sessionId = commandBus.dispatch(new CreateSessionCommand(
                "2024/2025",
                LocalDate.of(2024, 9, 2),
                LocalDate.of(2025, 7, 15)));

        // 2. Create class + open in session
        Long classId = commandBus.dispatch(new CreateClassCommand("JSS1A"));
        Long classSessionId = commandBus.dispatch(new OpenClassInSessionCommand(classId, sessionId));

        // 3. Create subjects
        Long mathsId = commandBus.dispatch(new CreateSubjectCommand("Mathematics"));
        Long litId   = commandBus.dispatch(new CreateSubjectCommand("Literature in English"));

        // 4. Add subjects to class (Maths compulsory, Lit elective)
        Long cssMathsId = commandBus.dispatch(new AddSubjectToClassCommand(classSessionId, mathsId, false));
        Long cssLitId   = commandBus.dispatch(new AddSubjectToClassCommand(classSessionId, litId, true));

        // 5. Create teacher and assign to subjects
        Long teacherId = commandBus.dispatch(new CreateTeacherCommand("Ibrahim", "Musa", "ibrahim@school.ng"));
        commandBus.dispatch(new AssignTeacherToClassCommand(teacherId, classSessionId, true));
        commandBus.dispatch(new AssignTeacherToSubjectCommand(teacherId, cssMathsId));
        commandBus.dispatch(new AssignTeacherToSubjectCommand(teacherId, cssLitId));

        // 6. Register students
        Long chukwuId = commandBus.dispatch(new CreateStudentCommand("Okafor", "Chukwuemeka", "chukwu@school.ng"));
        Long fatimaId = commandBus.dispatch(new CreateStudentCommand("Bello", "Fatima", "fatima@school.ng"));

        // 7. Enroll in class (auto-enrolls in Maths compulsory)
        commandBus.dispatch(new EnrollStudentInClassCommand(chukwuId, classSessionId));
        commandBus.dispatch(new EnrollStudentInClassCommand(fatimaId, classSessionId));

        // 8. Fatima opts into Literature
        commandBus.dispatch(new EnrollStudentInSubjectCommand(fatimaId, cssLitId));

        // 9. Create First Term
        Long termId = commandBus.dispatch(new CreateTermCommand(classSessionId, 1));

        // 10. Create examinations
        Long mathsExamId = commandBus.dispatch(new CreateExaminationCommand(termId, cssMathsId, LocalDate.of(2024, 12, 5), LocalTime.of(8,30), 60, BigDecimal.valueOf(40), BigDecimal.valueOf(60)));
        Long litExamId   = commandBus.dispatch(new CreateExaminationCommand(termId, cssLitId, LocalDate.of(2024, 12, 7), LocalTime.of(8,30), 60, BigDecimal.valueOf(40), BigDecimal.valueOf(60)));

        // 11. Record results
        commandBus.dispatch(new RecordResultCommand(mathsExamId, chukwuId, new BigDecimal("78.50")));
        commandBus.dispatch(new RecordResultCommand(mathsExamId, fatimaId, new BigDecimal("91.00")));
        commandBus.dispatch(new RecordResultCommand(litExamId,   fatimaId, new BigDecimal("74.00")));

        // 12. Query: Fatima's report card
        ReportCard fatimaCard = queryBus.dispatch(new GetStudentReportCardQuery(fatimaId, termId));

        assertThat(fatimaCard.studentName()).contains("Bello");
        assertThat(fatimaCard.termNumber()).isEqualTo(1);
        assertThat(fatimaCard.entries()).hasSize(2);
        assertThat(fatimaCard.entries())
                .extracting(ReportCardEntry::subjectName)
                .containsExactlyInAnyOrder("Mathematics", "Literature in English");

        // 13. Chukwuemeka's report card - only Maths (not enrolled in Lit)
        ReportCard chukwuCard = queryBus.dispatch(new GetStudentReportCardQuery(chukwuId, termId));
        assertThat(chukwuCard.entries()).hasSize(1);
        assertThat(chukwuCard.entries().get(0).subjectName()).isEqualTo("Mathematics");
        assertThat(chukwuCard.entries().get(0).grade()).isEqualTo("B");
    }
}
