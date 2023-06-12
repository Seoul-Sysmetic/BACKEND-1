package kr.co.moneybridge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.moneybridge.core.WithMockUser;
import kr.co.moneybridge.core.advice.MyLogAdvice;
import kr.co.moneybridge.core.advice.MyValidAdvice;
import kr.co.moneybridge.core.config.MyFilterRegisterConfig;
import kr.co.moneybridge.core.config.MySecurityConfig;
import kr.co.moneybridge.core.dummy.MockDummyEntity;
import kr.co.moneybridge.core.util.MyDateUtil;
import kr.co.moneybridge.core.util.MyMemberUtil;
import kr.co.moneybridge.core.util.RedisUtil;
import kr.co.moneybridge.dto.reservation.ReservationRequest;
import kr.co.moneybridge.dto.reservation.ReservationResponse;
import kr.co.moneybridge.model.pb.Branch;
import kr.co.moneybridge.model.pb.Company;
import kr.co.moneybridge.model.pb.PB;
import kr.co.moneybridge.model.reservation.LocationType;
import kr.co.moneybridge.model.reservation.ReservationGoal;
import kr.co.moneybridge.model.reservation.ReservationType;
import kr.co.moneybridge.model.user.User;
import kr.co.moneybridge.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@EnableAspectJAutoProxy
@Import({
        MyLogAdvice.class,
        MyValidAdvice.class,
        MyFilterRegisterConfig.class,
        MySecurityConfig.class,
        RedisUtil.class,
})
@WebMvcTest(
        controllers = {ReservationController.class}
)
public class ReservationControllerUnitTest extends MockDummyEntity {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @MockBean
    private ReservationService reservationService;
    @MockBean
    private RedisTemplate redisTemplate;
    @MockBean
    private MyMemberUtil myMemberUtil;

    @WithMockUser
    @Test
    public void get_reservation_base_test() throws Exception {
        // given
        Long pbId = 1L;
        Company company = newMockCompany(1L, "미래에셋");
        Branch branch = newMockBranch(1L, company, 1);
        PB pb = newMockPB(pbId, "이피비", branch);
        User user = newMockUser(1L, "lee");

        // stub
        Mockito.when(reservationService.getReservationBase(anyLong(), any()))
                .thenReturn(new ReservationResponse.ReservationBaseOutDTO(
                        new ReservationResponse.pbInfoDTO(
                                pb.getName(),
                                pb.getBranch().getName(),
                                pb.getBranch().getRoadAddress(),
                                pb.getBranch().getLatitude(),
                                pb.getBranch().getLongitude()
                        ),
                        new ReservationResponse.consultInfoDTO(
                                MyDateUtil.localTimeToString(pb.getConsultStart()),
                                MyDateUtil.localTimeToString(pb.getConsultEnd()),
                                pb.getConsultNotice()
                        ),
                        new ReservationResponse.userInfoDTO(
                                user.getName(),
                                user.getPhoneNumber(),
                                user.getEmail()
                        )
                ));

        // when
        ResultActions resultActions = mvc.perform(get("/user/reservation/{pbId}", pbId));
        String responseBody = resultActions.andReturn().getResponse().getContentAsString();
        System.out.println("테스트 : " + responseBody);

        // then
        resultActions.andExpect(jsonPath("$.data.pbInfo.pbName").value(pb.getName()));
        resultActions.andExpect(jsonPath("$.data.pbInfo.branchName").value(pb.getBranch().getName()));
        resultActions.andExpect(jsonPath("$.data.pbInfo.branchAddress").value(pb.getBranch().getRoadAddress()));
        resultActions.andExpect(jsonPath("$.data.pbInfo.branchLatitude").value(pb.getBranch().getLatitude()));
        resultActions.andExpect(jsonPath("$.data.pbInfo.branchLongitude").value(pb.getBranch().getLongitude()));
        resultActions.andExpect(jsonPath("$.data.consultInfo.consultStart").value(MyDateUtil.localTimeToString(pb.getConsultStart())));
        resultActions.andExpect(jsonPath("$.data.consultInfo.consultEnd").value(MyDateUtil.localTimeToString(pb.getConsultEnd())));
        resultActions.andExpect(jsonPath("$.data.consultInfo.notice").value(pb.getConsultNotice()));
        resultActions.andExpect(jsonPath("$.data.userInfo.userName").value(user.getName()));
        resultActions.andExpect(jsonPath("$.data.userInfo.userPhoneNumber").value(user.getPhoneNumber()));
        resultActions.andExpect(jsonPath("$.data.userInfo.userEmail").value(user.getEmail()));

        resultActions.andExpect(status().isOk());
    }

    @WithMockUser
    @Test
    public void apply_reservation_test() throws Exception {
        // given
        Long pbId = 1L;
        Company company = newMockCompany(1L, "미래에셋");
        Branch branch = newMockBranch(1L, company, 1);
        PB pb = newMockPB(pbId, "이피비", branch);
        User user = newMockUser(1L, "lee");
        ReservationRequest.ApplyReservationInDTO applyReservationInDTO = new ReservationRequest.ApplyReservationInDTO();
        applyReservationInDTO.setGoal1(ReservationGoal.PROFIT);
        applyReservationInDTO.setGoal2(ReservationGoal.RISK);
        applyReservationInDTO.setReservationType(ReservationType.VISIT);
        applyReservationInDTO.setLocationType(LocationType.BRANCH);
        applyReservationInDTO.setLocationName("미래에셋증권 용산wm점");
        applyReservationInDTO.setLocationAddress("서울특별시 용산구 한강로동 한강대로 92");
        applyReservationInDTO.setCandidateTime1("2023-05-15T09:00:00");
        applyReservationInDTO.setCandidateTime2("2023-05-15T10:00:00");
        applyReservationInDTO.setQuestion("2023-05-15T10:00:00");
        applyReservationInDTO.setUserName("lee");
        applyReservationInDTO.setUserPhoneNumber("01012345678");
        applyReservationInDTO.setUserEmail("lee@nate.com");
        String requestBody = om.writeValueAsString(applyReservationInDTO);

        // stub

        // when
        ResultActions resultActions = mvc.perform(post("/user/reservation/{pbId}", pbId)
                .content(requestBody)
                .contentType(MediaType.APPLICATION_JSON));
        String responseBody = resultActions.andReturn().getResponse().getContentAsString();
        System.out.println("테스트 : " + responseBody);

        // then
        resultActions.andExpect(status().isOk());
    }
}