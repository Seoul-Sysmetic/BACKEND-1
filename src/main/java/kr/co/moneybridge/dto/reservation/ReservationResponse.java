package kr.co.moneybridge.dto.reservation;

import lombok.Getter;
import lombok.Setter;

public class ReservationResponse {
    @Getter
    @Setter
    public static class ReservationBaseOutDTO{
        private String branchName;
        private String branchAddress;
        private String branchLatitude;
        private String branchLongitude;
        private String consultStart;
        private String consultEnd;
        private String notice;

        public ReservationBaseOutDTO(String branchName, String branchAddress, String branchLatitude, String branchLongitude, String consultStart, String consultEnd, String notice) {
            this.branchName = branchName;
            this.branchAddress = branchAddress;
            this.branchLatitude = branchLatitude;
            this.branchLongitude = branchLongitude;
            this.consultStart = consultStart;
            this.consultEnd = consultEnd;
            this.notice = notice;
        }
    }
}