package com.travelagent.agent.safety;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SensitiveInfoGuard {

    private static final List<SensitiveRule> RULES = List.of(
            new SensitiveRule("real_name_context", Pattern.compile("(?i)\\b(real\\s*name|guest\\s*name|ticket\\s*holder|identity\\s*required)\\b"), false),
            new SensitiveRule("prohibited_action", Pattern.compile("(?i)\\b(auto\\s*submit\\s*order|auto\\s*payment|auto\\s*refund|auto\\s*cancel\\s*order)\\b"), false),
            new SensitiveRule("mobile_phone", Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), true),
            new SensitiveRule("id_card", Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)"), true),
            new SensitiveRule("passport", Pattern.compile("(?i)\\b([EGP]\\d{8}|[A-Z]{2}\\d{7})\\b"), true),
            new SensitiveRule("verification_code", Pattern.compile("(?i)(验证码|校验码|code)[:：\\s]*\\d{4,8}"), true),
            new SensitiveRule("payment_secret", Pattern.compile("(?i)(支付密码|银行卡密码|cvv|安全码)[:：\\s]*\\S+"), true),
            new SensitiveRule("login_secret", Pattern.compile("(?i)(登录密码|账号密码|password)[:：\\s]*\\S+"), true),
            new SensitiveRule("real_name_context", Pattern.compile("(真实姓名|实名|入住人|购票人|预约人)"), false),
            new SensitiveRule("prohibited_action", Pattern.compile("(自动提交订单|自动支付|自动退款|自动取消订单|代我付款|帮我付款)"), false)
    );

    public ScanResult scan(String text) {
        String value = text == null ? "" : text;
        List<Finding> findings = new ArrayList<>();
        boolean highRisk = false;
        for (SensitiveRule rule : RULES) {
            var matcher = rule.pattern().matcher(value);
            while (matcher.find()) {
                findings.add(new Finding(rule.type(), mask(matcher.group()), rule.highRisk()));
                highRisk = highRisk || rule.highRisk();
            }
        }
        boolean manualActionRequired = !findings.isEmpty();
        return new ScanResult(findings, highRisk, manualActionRequired,
                manualActionRequired ? "Sensitive booking, payment or identity data must be handled manually by the user." : null);
    }

    public String sanitize(String text) {
        return sanitize(text, false);
    }

    public String sanitize(String text, boolean includeLowRisk) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String sanitized = text;
        for (SensitiveRule rule : RULES) {
            if (rule.highRisk() || includeLowRisk) {
                sanitized = rule.pattern().matcher(sanitized)
                        .replaceAll(Matcher.quoteReplacement("[REDACTED:" + rule.type() + "]"));
            }
        }
        return sanitized;
    }

    public SafetyPolicy defaultPolicy() {
        return new SafetyPolicy(
                true,
                false,
                false,
                false,
                List.of("real_name", "mobile_phone", "id_card", "passport", "hotel_guest", "ticket_real_name", "login_secret", "verification_code", "payment_info"),
                List.of("auto_fill_sensitive_info", "auto_submit_order", "auto_payment", "auto_cancel_order", "auto_refund", "store_high_risk_secret")
        );
    }

    private String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, Math.min(2, value.length())) + "****" + value.substring(value.length() - 2);
    }

    private record SensitiveRule(String type, Pattern pattern, boolean highRisk) {
    }

    public record Finding(String type, String maskedValue, boolean highRisk) {
    }

    public record ScanResult(List<Finding> findings,
                             boolean highRisk,
                             boolean manualActionRequired,
                             String safetyBoundaryNote) {
    }

    public record SafetyPolicy(boolean manualConfirmationRequired,
                               boolean autoFillSensitiveInfo,
                               boolean autoSubmitOrder,
                               boolean autoPayment,
                               List<String> sensitiveFields,
                               List<String> prohibitedActions) {
    }
}
