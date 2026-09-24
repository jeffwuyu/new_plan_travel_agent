package com.travelagent.service.task;

import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.agent.requirements.TravelRequirementParser;
import com.travelagent.exception.BusinessException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.TravelRequirementDraftMapper;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.dto.TravelRequirementRequests;
import com.travelagent.model.entity.TravelRequirementDraft;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TravelRequirementDraftService {
    private final TravelRequirementDraftMapper drafts;
    private final TravelRequirementParser parser;
    private final JsonUtil json;
    private final TaskService tasks;
    private final QuotaService quota;

    public TravelRequirementDraftService(TravelRequirementDraftMapper drafts, TravelRequirementParser parser,
                                         JsonUtil json, TaskService tasks, QuotaService quota) {
        this.drafts = drafts;
        this.parser = parser;
        this.json = json;
        this.tasks = tasks;
        this.quota = quota;
    }

    @Transactional
    public TravelRequirementRequests.Response create(Long userId, int userLevel, TravelRequirementRequests.Create request, String ip) {
        validateTimezone(request.timezone());
        if (request.idempotencyKey() != null) {
            TravelRequirementDraft prior = drafts.findByIdempotency(userId, request.idempotencyKey());
            if (prior != null) return response(prior);
        }
        TravelConstraints constraints = parser.parse(request.text());
        if (request.constraints() != null) constraints.mergeFrom(request.constraints());
        validate(constraints);
        TravelRequirementDraft draft = new TravelRequirementDraft();
        draft.setUserId(userId);
        draft.setRawText(request.text());
        draft.setConstraintsJson(json.toJson(constraints));
        draft.setQuestionsJson(json.toJson(questions(constraints)));
        draft.setStatus(ready(constraints) ? "READY" : "NEEDS_INPUT");
        draft.setIdempotencyKey(request.idempotencyKey());
        drafts.insert(draft);
        return response(drafts.findByIdAndUser(draft.getId(), userId));
    }

    @Transactional
    public TravelRequirementRequests.Response update(Long userId, Long id, TravelRequirementRequests.Update request) {
        TravelRequirementDraft current = owned(userId, id);
        if (current.getRevision() != request.expectedRevision()) throw new BusinessException(409, "REVISION_CONFLICT");
        String text = request.text() == null || request.text().isBlank() ? current.getRawText() : request.text();
        TravelConstraints constraints = parser.parse(text, json.fromJson(current.getConstraintsJson(), TravelConstraints.class));
        if (request.constraints() != null) constraints.mergeFrom(request.constraints());
        validateTimezone(request.timezone());
        validate(constraints);
        String status = ready(constraints) ? "READY" : "NEEDS_INPUT";
        int updated = drafts.updateIfRevision(id, userId, request.expectedRevision(), text, json.toJson(constraints),
                json.toJson(questions(constraints)), status);
        if (updated != 1) throw new BusinessException(409, "REVISION_CONFLICT");
        return response(owned(userId, id));
    }

    @Transactional
    public TravelRequirementRequests.Response confirm(Long userId, int userLevel, Long id,
                                                       TravelRequirementRequests.Confirm request, String ip) {
        TravelRequirementDraft draft = owned(userId, id);
        if ("CONFIRMED".equals(draft.getStatus())) return response(draft);
        if (draft.getRevision() != request.expectedRevision()) throw new BusinessException(409, "REVISION_CONFLICT");
        TravelConstraints c = json.fromJson(draft.getConstraintsJson(), TravelConstraints.class);
        validate(c);
        if (!ready(c)) throw new BusinessException(409, "REQUIREMENTS_NEED_INPUT");
        CreateTaskRequest create = new CreateTaskRequest();
        create.setRegion(c.getDestination());
        create.setUserIntent(c.getRawText() == null ? draft.getRawText() : c.getRawText());
        create.setStartLocationQuery(c.getDeparture());
        create.setEndLocationQuery(c.getDeparture());
        var startDate = c.getStartDate();
        var endDate = c.getEndDate() == null && c.getDays() != null
                ? startDate.plusDays(c.getDays() - 1L) : c.getEndDate();
        create.setStartTime(startDate.atTime(9, 0));
        create.setEndTime(endDate.atTime(18, 0));
        create.setTotalBudgetYuan(c.getBudgetYuan());
        create.setLodgingBudgetPerNightYuan(c.getBudgetYuan());
        create.setAdultCount(c.getPeopleCount());
        create.setPreferenceKeywords(c.getAttractionPreference());
        if (draft.getTaskUuid() != null) return response(draft);
        TaskResponse task = tasks.createTask(userId, userLevel, create, ip);
        int updated = drafts.confirm(id, userId, request.expectedRevision(), request.idempotencyKey(), task.getTaskUuid());
        if (updated != 1) throw new BusinessException(409, "REVISION_CONFLICT");
        return response(owned(userId, id));
    }

    private TravelRequirementDraft owned(Long userId, Long id) {
        TravelRequirementDraft draft = drafts.findByIdAndUser(id, userId);
        if (draft == null) throw new BusinessException(404, "Requirement draft not found");
        return draft;
    }

    private TravelRequirementRequests.Response response(TravelRequirementDraft draft) {
        if (draft == null) throw new BusinessException(404, "Requirement draft not found");
        return new TravelRequirementRequests.Response(draft.getId(), draft.getRevision(), draft.getStatus(),
                json.fromJson(draft.getConstraintsJson(), TravelConstraints.class),
                draft.getQuestionsJson() == null ? List.of() : json.fromJson(draft.getQuestionsJson(), List.class), draft.getTaskUuid());
    }

    private void validate(TravelConstraints c) {
        if (c.getStartDate() != null && c.getEndDate() != null && c.getEndDate().isBefore(c.getStartDate()))
            throw new BusinessException(400, "INVALID_TIME_RANGE");
        if (c.getDays() != null && c.getDays() < 1) throw new BusinessException(400, "INVALID_TIME_RANGE");
    }

    private void validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) return;
        try { ZoneId.of(timezone); } catch (Exception exception) { throw new BusinessException(400, "INVALID_TIMEZONE"); }
    }

    private List<String> questions(TravelConstraints c) {
        List<String> result = new ArrayList<>();
        for (var missing : c.getMissingFields()) if (missing.isRequired()) result.add(missing.getName());
        if (c.getStartDate() == null && c.getDays() != null) result.add("start_date");
        if (c.getDeparture() == null || c.getDeparture().isBlank()) result.add("departure");
        return result;
    }

    private boolean ready(TravelConstraints constraints) {
        return !constraints.isMustAsk()
                && constraints.getStartDate() != null
                && constraints.getBudgetYuan() != null
                && constraints.getPeopleCount() != null
                && constraints.getDeparture() != null && !constraints.getDeparture().isBlank();
    }
}
