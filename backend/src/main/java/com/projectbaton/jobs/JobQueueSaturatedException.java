package com.projectbaton.jobs;

public class JobQueueSaturatedException extends RuntimeException {
    public JobQueueSaturatedException() {
        super("The unfinished job queue has reached capacity");
    }
}
