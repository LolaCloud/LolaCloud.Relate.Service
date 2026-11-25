package com.miranda.luna.relate.domain.model;

public enum InstanceTier {
    MICRO_512MB(512, "0.5"),
    STANDARD_1GB(1024, "1.0"),
    PRO_2GB(2048, "2.0");

    private final int memoryMb;
    private final String cpuLimit;

    InstanceTier(int memoryMb, String cpuLimit) {
        this.memoryMb = memoryMb;
        this.cpuLimit = cpuLimit;
    }

    public int getMemoryMb() {
        return memoryMb;
    }

    public String getCpuLimit() {
        return cpuLimit;
    }
}
