package com.chris64233.cc.shelter;

import com.chris64233.cc.shelter.common.BusinessException;
import com.chris64233.cc.shelter.household.HouseholdService;
import com.chris64233.cc.shelter.household.dto.MemberRequest;
import com.chris64233.cc.shelter.household.dto.RegisterHouseholdRequest;
import com.chris64233.cc.shelter.shelter.RoomRepository;
import com.chris64233.cc.shelter.shelter.ShelterService;
import com.chris64233.cc.shelter.shelter.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.shelter.dto.CreateShelterRequest;
import com.chris64233.cc.shelter.stay.StayRepository;
import com.chris64233.cc.shelter.stay.StayService;
import com.chris64233.cc.shelter.stay.dto.CheckInRequest;
import com.chris64233.cc.shelter.stay.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StayConcurrencyTests {

    @Autowired
    StayService stayService;

    @Autowired
    ShelterService shelterService;

    @Autowired
    HouseholdService householdService;

    @Autowired
    RoomRepository roomRepository;

    @Autowired
    StayRepository stayRepository;

    private Long shelterWithRooms(int[][] rooms) {
        Long shelterId = shelterService.createShelter(new CreateShelterRequest("S-" + UUID.randomUUID())).id();
        for (int[] room : rooms) {
            shelterService.addRoom(shelterId, new CreateRoomRequest(room[0], room[1], room[2] == 1));
        }
        return shelterId;
    }

    private String household(int size) {
        String number = "H-" + UUID.randomUUID();
        List<MemberRequest> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            members.add(new MemberRequest("ID-" + UUID.randomUUID(), 30 + i, false));
        }
        householdService.register(new RegisterHouseholdRequest(number, members));
        return number;
    }

    private <T> List<T> runConcurrently(List<java.util.concurrent.Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Callable<T>> synchronizedTasks = tasks.stream()
                .<java.util.concurrent.Callable<T>>map(task -> () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return task.call();
                })
                .toList();
        List<Future<T>> futures = synchronizedTasks.stream().map(pool::submit).toList();
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    @Test
    void concurrentCheckInsNeverOversellRoom() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{101, 4, 0}});
        List<String> households = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            households.add(household(2));
        }

        List<java.util.concurrent.Callable<Object>> tasks = households.stream()
                .map(number -> (java.util.concurrent.Callable<Object>) () -> {
                    try {
                        return stayService.checkIn(new CheckInRequest("k-" + number, number, shelterId));
                    } catch (BusinessException ex) {
                        return ex.getCode();
                    }
                })
                .toList();

        List<Object> results = runConcurrently(tasks);

        long succeeded = results.stream().filter(r -> !(r instanceof String)).count();
        long rejected = results.stream().filter(r -> "NO_SUITABLE_ROOM".equals(r)).count();
        assertThat(succeeded).isEqualTo(2);
        assertThat(rejected).isEqualTo(2);
        assertThat(roomRepository.findByShelterIdOrderByRoomNumberAsc(shelterId).get(0).getOccupiedBeds())
                .isEqualTo(4);
    }

    @Test
    void concurrentSameIdempotencyKeyReturnsSameStay() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{101, 8, 0}});
        String householdNumber = household(2);
        CheckInRequest request = new CheckInRequest("shared-key", householdNumber, shelterId);

        List<Object> results = runConcurrently(List.of(
                () -> stayService.checkIn(request),
                () -> stayService.checkIn(request)));

        List<Long> distinctStayIds = results.stream()
                .map(r -> ((com.chris64233.cc.shelter.stay.StayOutcome) r).body().stayId())
                .distinct()
                .toList();
        assertThat(distinctStayIds).hasSize(1);
        assertThat(roomRepository.findByShelterIdOrderByRoomNumberAsc(shelterId).get(0).getOccupiedBeds())
                .isEqualTo(2);
    }

    @Test
    void concurrentTransfersSerializeWithoutDoubleReleaseOrDoubleStay() throws Exception {
        Long source = shelterWithRooms(new int[][]{{101, 4, 0}});
        Long target = shelterWithRooms(new int[][]{{101, 4, 0}});
        String householdNumber = household(2);
        stayService.checkIn(new CheckInRequest("ci-" + householdNumber, householdNumber, source));

        List<Object> results = runConcurrently(List.of(
                () -> {
                    try {
                        return stayService.transfer(householdNumber, new TransferRequest("t-a", target));
                    } catch (BusinessException ex) {
                        return ex.getCode();
                    }
                },
                () -> {
                    try {
                        return stayService.transfer(householdNumber, new TransferRequest("t-b", target));
                    } catch (BusinessException ex) {
                        return ex.getCode();
                    }
                }));

        long succeeded = results.stream().filter(r -> !(r instanceof String)).count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(roomRepository.findByShelterIdOrderByRoomNumberAsc(source).get(0).getOccupiedBeds()).isEqualTo(0);
        assertThat(roomRepository.findByShelterIdOrderByRoomNumberAsc(target).get(0).getOccupiedBeds()).isEqualTo(2);
    }
}
