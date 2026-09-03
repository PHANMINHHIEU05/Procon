package vn.ptit.procon.planner.v3;

import org.junit.jupiter.api.Test;

class V3Phase24DumpTest {

    @Test
    void dump() {
        System.out.println(V3Phase24ReportPrinter.render(new V3Phase24Analysis().table()));
    }
}
