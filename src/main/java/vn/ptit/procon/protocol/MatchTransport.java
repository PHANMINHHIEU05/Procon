package vn.ptit.procon.protocol;

import vn.ptit.procon.model.Model.DayState;
import vn.ptit.procon.model.Model.MatchResult;
import vn.ptit.procon.model.Model.Setup;
import vn.ptit.procon.model.Model.SubmissionAck;

import java.io.IOException;

public interface MatchTransport extends AutoCloseable {
    Setup awaitSetup(long deadlineNanos) throws IOException, InterruptedException;
    SubmissionAck submitAssignment(int[] roles) throws IOException, InterruptedException;
    DayState awaitNextDay(int lastDay, long deadlineNanos) throws IOException, InterruptedException;
    SubmissionAck submitActions(int day, int[][] actions) throws IOException, InterruptedException;
    MatchResult awaitResult(long deadlineNanos) throws IOException, InterruptedException;
    @Override void close();
}
