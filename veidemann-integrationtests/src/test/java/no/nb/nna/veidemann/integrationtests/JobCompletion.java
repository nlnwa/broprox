package no.nb.nna.veidemann.integrationtests;

import com.rethinkdb.net.Cursor;
import no.nb.nna.veidemann.api.ControllerProto;
import no.nb.nna.veidemann.api.MessagesProto.CrawlExecutionStatus;
import no.nb.nna.veidemann.api.MessagesProto.JobExecutionStatus;
import no.nb.nna.veidemann.api.StatusGrpc;
import no.nb.nna.veidemann.api.StatusProto.ExecutionId;
import no.nb.nna.veidemann.api.StatusProto.ListExecutionsRequest;
import no.nb.nna.veidemann.commons.util.ApiTools.ListReplyWalker;
import no.nb.nna.veidemann.db.ProtoUtils;
import no.nb.nna.veidemann.db.RethinkDbAdapter.TABLES;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RunnableFuture;
import java.util.stream.StreamSupport;

public class JobCompletion extends ForkJoinTask<JobExecutionStatus> implements RunnableFuture<JobExecutionStatus> {
    final StatusGrpc.StatusBlockingStub statusClient;

    final String jobExecutionId;

    final List<String> eIds;

    JobExecutionStatus result;

    public static JobCompletion executeJob(StatusGrpc.StatusBlockingStub statusClient, ControllerProto.RunCrawlRequest crawlRequest) {
        return (JobCompletion) ForkJoinPool.commonPool().submit((ForkJoinTask) new JobCompletion(statusClient, crawlRequest));
    }


    JobCompletion(StatusGrpc.StatusBlockingStub statusClient, ControllerProto.RunCrawlRequest request) {
        this.statusClient = statusClient;

        ControllerProto.RunCrawlReply crawlReply = MultiSiteCrawlIT.controllerClient.runCrawl(request);

        jobExecutionId = crawlReply.getJobExecutionId();
        eIds = new ArrayList<>();

        ListReplyWalker<ListExecutionsRequest, CrawlExecutionStatus> w = new ListReplyWalker<>();
        w.walk(ListExecutionsRequest.newBuilder().setJobExecutionId(jobExecutionId),
                r -> MultiSiteCrawlIT.statusClient.listExecutions(r),
                s -> eIds.add(s.getId()));
    }

    @Override
    public JobExecutionStatus getRawResult() {
        return result;
    }

    @Override
    protected void setRawResult(JobExecutionStatus value) {
        result = value;
    }

    @Override
    public void run() {
        invoke();
    }

    @Override
    protected boolean exec() {
        try {
            Cursor<Map<String, Object>> cursor = MultiSiteCrawlIT.db.executeRequest("list", MultiSiteCrawlIT.r.table(TABLES.JOB_EXECUTIONS.name)
                    .get(jobExecutionId)
                    .changes());

            StreamSupport.stream(cursor.spliterator(), false)
                    .filter(e -> e.containsKey("new_val"))
                    .map(e -> ProtoUtils
                            .rethinkToProto((Map<String, Object>) e.get("new_val"), JobExecutionStatus.class))
                    .forEach(e -> {
                        if (isEnded(e)) {
                            System.out.println("Job completed");
                            cursor.close();
                        }
                    });

            setRawResult(statusClient.getJobExecution(ExecutionId.newBuilder().setId(jobExecutionId).build()));
            return true;
        } catch (Error err) {
            throw err;
        } catch (RuntimeException rex) {
            throw rex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private boolean isEnded(JobExecutionStatus execution) {
        switch (execution.getState()) {
            case CREATED:
            case RUNNING:
                return false;
            default:
                return true;
        }
    }

}
