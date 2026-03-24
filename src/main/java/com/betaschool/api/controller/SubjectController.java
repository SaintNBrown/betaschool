package com.betaschool.api.controller;

import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.AcademicCommand.*;
import com.betaschool.query.model.AcademicQuery.*;
import com.betaschool.query.model.AcademicQueryResult.*;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/subjects")
@RequiredArgsConstructor
@Tag(name = "Subjects", description = "Subject management")
public class SubjectController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    @PostMapping
    @Operation(summary = "Create a new subject")
    public ResponseEntity<ApiResponse<Long>> create(@RequestParam String name) {
        Long id = commandBus.dispatch(new CreateSubjectCommand(name));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @GetMapping
    @Operation(summary = "Get all subjects")
    public ResponseEntity<ApiResponse<List<SubjectSummary>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetAllSubjectsQuery())));
    }
}
