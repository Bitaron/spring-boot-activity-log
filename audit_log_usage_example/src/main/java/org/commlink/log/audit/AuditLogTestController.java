package org.commlink.log.audit;

import io.github.bitaron.auditlog.annotation.Audit;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;


@RestController
public class AuditLogTestController {

    @Audit(auditType = "test", actionName = "test-action", actionType = "test-type",
            templates = {"test_template"})
    @GetMapping("/test")
    public ResponseEntity<Response> test(HttpServletRequest request) {
        return ResponseEntity.ok(new Response(10, getTestData()));
    }

    /**
     * Demonstrates the {@code @AfterThrowing} advice path: the exception propagates to the
     * caller as a 500 exactly as it would without the starter, and a second audit_log row is
     * recorded via {@code logMethodActionException} regardless.
     */
    @Audit(auditType = "test", actionName = "test-action-fail", actionType = "test-type",
            templates = {"test_template"})
    @GetMapping("/test/fail")
    public ResponseEntity<Response> testFail(HttpServletRequest request) {
        throw new RuntimeException("Test exception");
    }

    private static TestData getTestData() {
        TestData.L2 l2 = new TestData.L2("l2_data");
        TestData.L1 l1 = new TestData.L1(l2, "l1_data");
        return new TestData(l1, "test");
    }


    @Data
    public static class Response implements Serializable {
        public Integer value;
        public TestData testData;

        public Response(Integer value, TestData testData) {
            this.value = value;
            this.testData = testData;
        }
    }

    @Data
    public static class TestData implements Serializable {
        public L1 l1;
        public String test;

        public TestData(L1 l1, String test) {
            this.l1 = l1;
            this.test = test;
        }


        @Data
        public static class L1 implements Serializable {
            public L2 l2;
            public String l1;

            public L1(L2 l2, String l1) {
                this.l2 = l2;
                this.l1 = l1;
            }
        }


        @Data
        public static class L2 implements Serializable {
            public String l2;

            public L2(String l2) {
                this.l2 = l2;
            }
        }


    }

}
