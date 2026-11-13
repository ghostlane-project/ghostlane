//go:build darwin && cgo

package main

/*
#include <libproc.h>
#include <sys/resource.h>
#include <unistd.h>

static unsigned long long lab_phys_footprint(void) {
	struct rusage_info_v4 ri;
	if (proc_pid_rusage(getpid(), RUSAGE_INFO_V4, (rusage_info_t *)&ri) != 0) {
		return 0;
	}
	return ri.ri_phys_footprint;
}
*/
import "C"

// selfFootprint is phys_footprint, the figure jetsam judges and the one
// MemoryWatch.swift reads (TASK_VM_INFO phys_footprint) before it releases.
func selfFootprint() uint64 { return uint64(C.lab_phys_footprint()) }
