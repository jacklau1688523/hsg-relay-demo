package com.hsg.relay.routing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 全局评分权重配置接口（页面：全局配置 → 评分权重与路由排序）。
 * 保存草稿 / 删除草稿 / 发布 / 停用都要求提交操作人并记入审计；权重合法性以服务端校验为准，校验不通过返回 400。
 * match 只做查询，不改变任何业务数据。
 */
@RestController
@RequestMapping("/api/routing/score-weights")
public class RoutingScoreWeightController {
    public record MatchRequest(String transactionType, String clientTier, LocalDateTime at) {
    }

    private final RoutingScoreWeightService service;

    public RoutingScoreWeightController(RoutingScoreWeightService service) {
        this.service = service;
    }

    /** 权重列表（全部状态），可按交易类型、客户层级、发布状态筛选。 */
    @GetMapping
    public List<RoutingScoreWeightService.View> list(@RequestParam(required = false) String transactionType,
                                                     @RequestParam(required = false) String clientTier,
                                                     @RequestParam(required = false) String status) {
        return service.list(transactionType, clientTier, status);
    }

    /** 交易类型、客户层级字典（取自字段库 clientSegment 的可选值 + 已配置过的层级）、兜底配置是否生效。 */
    @GetMapping("/options")
    public RoutingScoreWeightService.Options options() {
        return service.options();
    }

    /** 配置版本记录：已发布 / 已停用的版本。 */
    @GetMapping("/versions")
    public List<RoutingScoreWeightService.View> versions() {
        return service.versions();
    }

    @GetMapping("/audits")
    public List<RoutingScoreWeightService.AuditView> audits() {
        return service.audits();
    }

    /** 权重匹配模拟：返回 P1 / P2 / P3 的逐步判断和实际命中的配置；没有命中时 matched 为空、error 说明原因。 */
    @PostMapping("/match")
    public ScoreWeightMatcher.Trace match(@RequestBody MatchRequest request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        return service.match(request.transactionType(), request.clientTier(), request.at());
    }

    /** 新增或更新草稿（id 为空是新增）。 */
    @PostMapping("/drafts")
    public RoutingScoreWeightService.View saveDraft(@RequestBody RoutingScoreWeightService.DraftRequest request) {
        return service.saveDraft(request);
    }

    @DeleteMapping("/drafts/{id}")
    public Map<String, Object> deleteDraft(@PathVariable long id, @RequestParam String operator) {
        service.deleteDraft(id, operator);
        return Map.of("deleted", true, "id", id);
    }

    @PostMapping("/drafts/{id}/publish")
    public RoutingScoreWeightService.View publish(@PathVariable long id, @RequestBody RoutingScoreWeightService.PublishRequest request) {
        return service.publish(id, request);
    }

    @PostMapping("/{id}/disable")
    public RoutingScoreWeightService.View disable(@PathVariable long id, @RequestBody RoutingScoreWeightService.PublishRequest request) {
        return service.disable(id, request);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
