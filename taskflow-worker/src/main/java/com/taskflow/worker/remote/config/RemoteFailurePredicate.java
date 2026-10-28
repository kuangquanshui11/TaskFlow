package com.taskflow.worker.remote.config;
import java.util.function.Predicate;
import feign.FeignException;
public class RemoteFailurePredicate implements Predicate<Throwable> {
    @Override public boolean test(Throwable failure) {
        int status=com.taskflow.worker.remote.model.RemoteErrors.status(failure);
        return status>=500 || status==408 || status==429;
    }
}
