package org.commlink.log;

import io.github.bitaron.auditLog.annotation.Audit;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;


@RestController
public class Test {

    @Audit(type = "test")
    @GetMapping("/test")
    public ResponseEntity test(HttpServletRequest request) throws InterruptedException {
        TestData testData = getTestData();
        //   useAnnotationException(testData, request);
        return useAnnotationValid(testData, request);

    }

    public void useAnnotationException(TestData testData, HttpServletRequest request) throws InterruptedException {
        throw new RuntimeException("Test exception");
    }


    public ResponseEntity useAnnotationValid(TestData testData, HttpServletRequest request) throws InterruptedException {
        return ResponseEntity.ok(new Response(10, getTestData()));
    }

    public static TestData getTestData() {
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
