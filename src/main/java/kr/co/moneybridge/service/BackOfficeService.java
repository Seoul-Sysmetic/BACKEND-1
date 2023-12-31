package kr.co.moneybridge.service;

import kr.co.moneybridge.core.annotation.MyLog;
import kr.co.moneybridge.core.exception.Exception400;
import kr.co.moneybridge.core.exception.Exception404;
import kr.co.moneybridge.core.exception.Exception500;
import kr.co.moneybridge.core.util.GeoCodingUtil;
import kr.co.moneybridge.core.util.MyMemberUtil;
import kr.co.moneybridge.core.util.MyMsgUtil;
import kr.co.moneybridge.core.util.S3Util;
import kr.co.moneybridge.dto.PageDTO;
import kr.co.moneybridge.dto.backOffice.BackOfficeRequest;
import kr.co.moneybridge.dto.backOffice.BackOfficeResponse;
import kr.co.moneybridge.dto.backOffice.FullAddress;
import kr.co.moneybridge.dto.reservation.ReservationResponse;
import kr.co.moneybridge.model.Role;
import kr.co.moneybridge.model.backoffice.FrequentQuestion;
import kr.co.moneybridge.model.backoffice.FrequentQuestionRepository;
import kr.co.moneybridge.model.backoffice.Notice;
import kr.co.moneybridge.model.backoffice.NoticeRepository;
import kr.co.moneybridge.model.board.BoardBookmarkRepository;
import kr.co.moneybridge.model.board.BoardRepository;
import kr.co.moneybridge.model.board.ReReplyRepository;
import kr.co.moneybridge.model.board.ReplyRepository;
import kr.co.moneybridge.model.pb.*;
import kr.co.moneybridge.model.reservation.*;
import kr.co.moneybridge.model.user.User;
import kr.co.moneybridge.model.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.mail.internet.MimeMessage;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Service
public class BackOfficeService {
    private final FrequentQuestionRepository frequentQuestionRepository;
    private final NoticeRepository noticeRepository;
    private final PBRepository pbRepository;
    private final MyMemberUtil myMemberUtil;
    private final JavaMailSender javaMailSender;
    private final MyMsgUtil myMsgUtil;
    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;
    private final ReviewRepository reviewRepository;
    private final StyleRepository styleRepository;
    private final BoardRepository boardRepository;
    private final BoardBookmarkRepository boardBookmarkRepository;
    private final ReplyRepository replyRepository;
    private final ReReplyRepository reReplyRepository;
    private final BranchRepository branchRepository;
    private final CompanyRepository companyRepository;
    private final S3Util s3Util;
    private final GeoCodingUtil geoCodingUtil;

    @MyLog
    @Transactional
    public void addBranch(BackOfficeRequest.BranchInDTO branchInDTO) {
        Company company = companyRepository.findById(branchInDTO.getCompanyId()).orElseThrow(
                () -> new Exception400("companyId", "없는 증권회사의 id입니다")
        );

        try {
            // 온라인으로만 운영되는 증권사의 경우
            if (branchInDTO.getAddress() == null || branchInDTO.getAddress().isEmpty()) {
                branchRepository.save(branchInDTO.toDefaultEntity(company));
                return;
            }
            FullAddress address = geoCodingUtil.getFullAddress(branchInDTO.getAddress());
            branchRepository.save(branchInDTO.toEntity(company, address));
        } catch (Exception e) {
            throw new Exception500("지점 저장 실패 : " + e);
        }
    }

    @MyLog
    @Transactional
    public void deleteReReply(Long id) {
        // reReply 삭제
        reReplyRepository.deleteById(id);
    }

    @MyLog
    @Transactional
    public void deleteReply(Long id) {
        // reply를 연관관계로 가지고 있는 reReply도 삭제
        reReplyRepository.deleteByReplyId(id);

        // reply 삭제
        replyRepository.deleteById(id);
    }

    @MyLog
    @Transactional
    public void deleteBoard(Long id) {
        // s3에서 컨텐츠 썸네일도 삭제
        deleteThumbnail(boardRepository.findThumbnailByBoardId(id));

        // board를 연관관계로 가지고 있는 boardBookmark삭제
        boardBookmarkRepository.deleteByBoardId(id);

        replyRepository.findAllByBoardId(id).stream().forEach(reply -> {
            // reply를 지우니, reply를 연관관계로 가지고 있는 reReply도 삭제
            reReplyRepository.deleteByReplyId(reply.getId());
        });
        // board를 연관관계로 가지고 있는 reply삭제
        replyRepository.deleteByBoardId(id);

        // board 삭제
        boardRepository.deleteById(id);
    }

    private void deleteThumbnail(Optional<String> thumbnail) {
        if (thumbnail.isPresent()) {
            s3Util.delete(thumbnail.get());
        }
    }

    @MyLog
    public BackOfficeResponse.ReservationTotalCountDTO getReservationsCount() {
        return new BackOfficeResponse.ReservationTotalCountDTO(
                reservationRepository.countByProcess(ReservationProcess.APPLY),
                reservationRepository.countByProcess(ReservationProcess.CONFIRM),
                reservationRepository.countByProcess(ReservationProcess.COMPLETE),
                reviewRepository.count(),
                pbRepository.countByStatus(PBStatus.PENDING));
    }

    @MyLog
    @Transactional
    public PageDTO<BackOfficeResponse.ReservationTotalDTO> getReservations(Pageable pageable) {
        Page<Reservation> reservationPG = reservationRepository.findAll(pageable);
        List<BackOfficeResponse.ReservationTotalDTO> list = reservationPG.getContent().stream().map(
                reservation -> {
                    BackOfficeResponse.ReviewTotalDTO reviewTotalDTO = null;
                    Optional<Review> reviewOP = reviewRepository.findByReservationId(reservation.getId());
                    if (!reviewOP.isEmpty()) {
                        reviewTotalDTO =
                                new BackOfficeResponse.ReviewTotalDTO(reviewOP.get(), styleRepository
                                        .findAllByReviewId(reviewOP.get().getId()).stream().map(style ->
                                                new ReservationResponse.StyleDTO(style.getStyle()))
                                        .collect(Collectors.toList()));
                    }
                    return new BackOfficeResponse.ReservationTotalDTO(reservation,
                            new BackOfficeResponse.UserDTO(reservation.getUser()),
                            new BackOfficeResponse.PBDTO(reservation.getPb()),
                            reviewTotalDTO);
                }).collect(Collectors.toList());
        return new PageDTO<>(list, reservationPG, Reservation.class);
    }

    @MyLog
    @Transactional
    public void forceWithdraw(Long memberId, Role role) {
        try{
            myMemberUtil.deleteById(memberId, role);
        }catch (Exception e){

        }
    }

    @MyLog
    @Transactional
    public void authorizeAdmin(Long userId, Boolean admin) {
        User userPS = userRepository.findById(userId).orElseThrow(
                () -> new Exception404("존재하지 않는 투자자입니다.")
        );
        userPS.authorize(admin);
    }

    @MyLog
    public BackOfficeResponse.CountDTO getMembersCount() {
        return new BackOfficeResponse.CountDTO(
                userRepository.count(), pbRepository.countByStatus(PBStatus.ACTIVE));
    }

    @MyLog
    public PageDTO<BackOfficeResponse.MemberOutDTO> getMembers(String type, Pageable pageable) {
        if (type.equals("user")) {
            Page<User> userPG = userRepository.findAll(pageable);
            List<BackOfficeResponse.MemberOutDTO> list = userPG.getContent().stream().map(user ->
                    new BackOfficeResponse.MemberOutDTO(user)).collect(Collectors.toList());
            return new PageDTO<>(list, userPG, User.class);
        }

        Page<PB> pbPG = pbRepository.findAllByStatus(PBStatus.ACTIVE, pageable);
        List<BackOfficeResponse.MemberOutDTO> list = pbPG.getContent().stream().map(pb ->
                new BackOfficeResponse.MemberOutDTO(pb)).collect(Collectors.toList());
        return new PageDTO<>(list, pbPG, PB.class);
    }

    @MyLog
    @Transactional
    public void approvePB(Long pbId, Boolean approve) {
        PB pbPS = pbRepository.findById(pbId).orElseThrow(
                () -> new Exception404("존재하지 않는 PB입니다.")
        );
        if (!pbPS.getStatus().equals(PBStatus.PENDING)) {
            throw new Exception400("pbId", "이미 승인 완료된 PB입니다.");
        }
        String subject = myMsgUtil.getSubjectApprove();
        String msg = myMsgUtil.getMsgApprove();
        if (approve == false) {
            myMemberUtil.deleteById(pbId, Role.PB); // 탈퇴와 동일하게 삭제
            subject = myMsgUtil.getSubjectReject();
            msg = myMsgUtil.getMsgReject();
        } else {
            pbPS.approved();
        }
        // 이메일 알림
        try {
            MimeMessage message = myMsgUtil.createMessage(pbPS.getEmail(), subject, msg);
            javaMailSender.send(message);
        } catch (Exception e) {
            throw new Exception500("이메일 알림 전송 실패 " + e.getMessage());
        }
    }

    @MyLog
    public PageDTO<BackOfficeResponse.PBPendingDTO> getPBPending(Pageable pageable) {
        Page<PB> pbPG = pbRepository.findAllByStatus(PBStatus.PENDING, pageable);
        List<BackOfficeResponse.PBPendingDTO> list = pbPG.getContent().stream().map(pb ->
                        new BackOfficeResponse.PBPendingDTO(pb, pb.getBranch().getName()))
                .collect(Collectors.toList());
        return new PageDTO<>(list, pbPG, PB.class);
    }


    @MyLog
    public PageDTO<BackOfficeResponse.NoticeDTO> getNotices(Pageable pageable) {
        Page<Notice> noticePG = noticeRepository.findAll(pageable);
        List<BackOfficeResponse.NoticeDTO> list = noticePG.getContent().stream().map(notice ->
                new BackOfficeResponse.NoticeDTO(notice)).collect(Collectors.toList());
        return new PageDTO<>(list, noticePG, Notice.class);
    }

    @MyLog
    public BackOfficeResponse.NoticeDTO getNotice(Long noticeId) {
        Notice noticePS = noticeRepository.findById(noticeId).orElseThrow(
                () -> new Exception404("존재하지 않는 공지사항입니다.")
        );

        return new BackOfficeResponse.NoticeDTO(noticePS);
    }

    @MyLog
    @Transactional
    public void addNotice(BackOfficeRequest.AddNoticeDTO addNoticeDTO) {
        try {
            noticeRepository.save(addNoticeDTO.toEntity());
        } catch (Exception e) {
            throw new Exception500("공지사항 저장 실패 : " + e.getMessage());
        }
    }

    @MyLog
    @Transactional
    public void updateNotice(Long noticeId, BackOfficeRequest.UpdateNoticeDTO updateNoticeDTO) {
        Notice noticePS = noticeRepository.findById(noticeId).orElseThrow(
                () -> new Exception404("존재하지 않는 공지사항입니다.")
        );

        try {
            noticePS.updateTitle(updateNoticeDTO.getTitle());
            noticePS.updateContent(updateNoticeDTO.getContent());
        } catch (Exception e) {
            throw new Exception500("공지사항 수정 실패 : " + e.getMessage());
        }
    }

    @MyLog
    @Transactional
    public void deleteNotice(Long noticeId) {
        noticeRepository.findById(noticeId).orElseThrow(
                () -> new Exception404("존재하지 않는 공지사항입니다.")
        );

        try {
            noticeRepository.deleteById(noticeId);
        } catch (Exception e) {
            throw new Exception500("공지사항 삭제 실패 : " + e.getMessage());
        }
    }

    @MyLog
    public PageDTO<BackOfficeResponse.FAQDTO> getFAQs(Pageable pageable) {
        Page<FrequentQuestion> faqPG = frequentQuestionRepository.findAll(pageable);
        List<BackOfficeResponse.FAQDTO> list = faqPG.getContent().stream().map(faq ->
                new BackOfficeResponse.FAQDTO(faq)).collect(Collectors.toList());
        return new PageDTO<>(list, faqPG, FrequentQuestion.class);
    }

    @MyLog
    public BackOfficeResponse.FAQDTO getFAQ(Long faqId) {
        FrequentQuestion faqPS = frequentQuestionRepository.findById(faqId).orElseThrow(
                () -> new Exception404("존재하지 않는 FAQ입니다.")
        );

        return new BackOfficeResponse.FAQDTO(faqPS);
    }

    @MyLog
    @Transactional
    public void addFAQ(BackOfficeRequest.AddFAQDTO addFAQDTO) {
        try {
            frequentQuestionRepository.save(addFAQDTO.toEntity());
        } catch (Exception e) {
            throw new Exception500("FAQ 저장 실패 : " + e.getMessage());
        }
    }

    @MyLog
    @Transactional
    public void updateFAQ(Long faqId, BackOfficeRequest.UpdateFAQDTO updateFAQDTO) {
        FrequentQuestion frequentQuestionPS = frequentQuestionRepository.findById(faqId).orElseThrow(
                () -> new Exception404("존재하지 않는 FAQ입니다.")
        );

        try {
            frequentQuestionPS.updateLabel(updateFAQDTO.getLabel());
            frequentQuestionPS.updateTitle(updateFAQDTO.getTitle());
            frequentQuestionPS.updateContent(updateFAQDTO.getContent());
        } catch (Exception e) {
            throw new Exception500("FAQ 수정 실패 : " + e.getMessage());
        }
    }

    @MyLog
    @Transactional
    public void deleteFAQ(Long faqId) {
        frequentQuestionRepository.findById(faqId).orElseThrow(
                () -> new Exception404("존재하지 않는 FAQ입니다.")
        );

        try {
            frequentQuestionRepository.deleteById(faqId);
        } catch (Exception e) {
            throw new Exception500("FAQ 수정 실패 : " + e.getMessage());
        }
    }
}
