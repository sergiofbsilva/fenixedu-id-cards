package org.fenixedu.idcards.domain;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.fenixedu.bennu.core.domain.Bennu;
import org.fenixedu.bennu.core.domain.User;
import org.fenixedu.bennu.core.signals.Signal;
import org.fenixedu.idcards.dto.RequestedCardBean;
import org.fenixedu.santandersdk.dto.CardPreviewBean;
import org.fenixedu.santandersdk.dto.CreateRegisterResponse;
import org.fenixedu.santandersdk.dto.CreateRegisterResponse.ErrorType;
import org.fenixedu.santandersdk.dto.GetRegisterResponse;
import org.joda.time.DateTime;
import org.joda.time.Days;

import com.google.common.base.Strings;
import com.google.common.io.BaseEncoding;

import pt.ist.fenixframework.Atomic;
import pt.ist.fenixframework.Atomic.TxMode;

public class SantanderEntry extends SantanderEntry_Base {

    public static final String STATE_CHANGED = "fenixedu.idcards.domain.santanderEntry.stateChanged";

    public static Comparator<SantanderEntry> COMPARATOR_BY_CREATED_DATE = (p1, p2) -> {
        DateTime date1 = p1.getCreationDate();
        DateTime date2 = p2.getCreationDate();
        return date1.compareTo(date2);
    };

    public static Comparator<SantanderEntry> REVERSE_COMPARATOR_BY_CREATED_DATE = COMPARATOR_BY_CREATED_DATE.reversed();

    @Atomic(mode = TxMode.WRITE)
    public static SantanderEntry importEntry(User user, RequestedCardBean requestedCardBean) {
        if (hasMifare(user, requestedCardBean.getMifare()))
            return null;

        return new SantanderEntry(user, requestedCardBean);
    }

    private SantanderEntry(User user, RequestedCardBean requestedCardBean) {
        setBennu(Bennu.getInstance());
        SantanderEntry currentEntry = user.getCurrentSantanderEntry();
        if (currentEntry != null) {
            setPrevious(currentEntry);
            currentEntry.setNext(this);
        }
        setUser(user);

        setRequestLine(requestedCardBean.getRequestLine());
        setResponseLine("");
        setErrorDescription("");
        SantanderCardInfo cardInfo = new SantanderCardInfo();
        cardInfo.setIdentificationNumber(requestedCardBean.getIdentificationNumber());
        cardInfo.setCardName(requestedCardBean.getCardName());
        cardInfo.setMifareNumber(requestedCardBean.getMifare());
        cardInfo.setRole(requestedCardBean.getRole());
        cardInfo.setSerialNumber(requestedCardBean.getCardSerialNumber());

        DateTime cardExpiryTime = requestedCardBean.getExpiryDate();
        cardInfo.setExpiryDate(cardExpiryTime);

        String photo = requestedCardBean.getPhoto();
        if (photo != null && photo.length() > 0)
            cardInfo.setPhoto(BaseEncoding.base64().decode(photo));

        setSantanderCardInfo(cardInfo);

        DateTime requestDate = requestedCardBean.getRequestDate();

        updateState(SantanderCardState.PENDING, requestDate);
        updateState(SantanderCardState.NEW, requestDate);
        updateState(SantanderCardState.ISSUED, requestedCardBean.getProductionDate());

        if (DateTime.now().isAfter(cardExpiryTime))
            updateState(SantanderCardState.EXPIRED, cardExpiryTime);
    }

    public SantanderEntry(User user, CardPreviewBean cardPreviewBean, PickupLocation pickupLocation) {
        setBennu(Bennu.getInstance());
        SantanderEntry currentEntry = user.getCurrentSantanderEntry();
        if (currentEntry != null) {
            setPrevious(currentEntry);
            currentEntry.setNext(this);
        }
        setUser(user);

        reset(cardPreviewBean, pickupLocation);
    }

    public CardPreviewBean getCardPreviewBean() {
        CardPreviewBean cardPreviewBean = new CardPreviewBean();
        SantanderCardInfo card = getSantanderCardInfo();
        cardPreviewBean.setCardName(card.getCardName());
        cardPreviewBean.setExpiryDate(card.getExpiryDate());
        cardPreviewBean.setRole(card.getRole());
        cardPreviewBean.setIdentificationNumber(card.getIdentificationNumber());
        cardPreviewBean.setPhoto(card.getPhoto());
        cardPreviewBean.setRequestLine(getRequestLine());
        return cardPreviewBean;
    }

    public DateTime getCreationDate() {
        return getSantanderCardInfo().getFirstTransictionDate();
    }

    public void reset(CardPreviewBean cardPreviewBean, PickupLocation pickupLocation) {
        setRequestLine(cardPreviewBean.getRequestLine());
        setResponseLine("");
        setErrorDescription("");
        setSantanderCardInfo(new SantanderCardInfo(cardPreviewBean, pickupLocation));
        updateState(SantanderCardState.PENDING);
    }
    
    @Atomic(mode = TxMode.WRITE)
    public void saveResponse(CreateRegisterResponse response) {

        if (response.wasRegisterSuccessful()) {
            update(SantanderCardState.NEW, response);
            return;
        }

        ErrorType errorType = response.getErrorType();
        switch (errorType) {
            case REQUEST_REFUSED:
                update(SantanderCardState.IGNORED, response);
                break;
            case SANTANDER_COMMUNICATION:
                update(SantanderCardState.PENDING, response);
                break;
            default:
                break;
        }
        setLastUpdate(DateTime.now());
    }

    private void update(SantanderCardState state, CreateRegisterResponse response) {
        updateStateAndNotify(state, DateTime.now());
        setResponseLine(Strings.isNullOrEmpty(response.getResponseLine()) ? "" : response.getResponseLine());
        setErrorDescription(Strings.isNullOrEmpty(response.getErrorDescription()) ? "" : response.getErrorDescription());
    }

    @Atomic(mode = TxMode.WRITE)
    public void updateIssued(GetRegisterResponse registerData) {
        SantanderCardInfo cardInfo = getSantanderCardInfo();
        cardInfo.setMifareNumber(registerData.getMifare());
        cardInfo.setSerialNumber(registerData.getSerialNumber());
        if (cardInfo.getExpiryDate() == null)
            cardInfo.setExpiryDate(registerData.getExpiryDate());
        DateTime expeditionDate = registerData.getExpeditionDate() == null ? DateTime.now() : registerData.getExpeditionDate();
        updateStateAndNotify(SantanderCardState.ISSUED, expeditionDate);
    }

    @Atomic(mode = TxMode.WRITE)
    public void updateState(SantanderCardState state) {
        updateState(state, DateTime.now());
    }

    public void updateState(SantanderCardState state, DateTime time) {
        if (getState() != state) {
            createSantanderCardStateTransition(state, time);
            setState(state);
        }
        setLastUpdate(time);
    }

    @Atomic(mode = TxMode.WRITE)
    public void updateStateAndNotify(SantanderCardState state) {
        updateStateAndNotify(state, DateTime.now());
    }

    public void updateStateAndNotify(SantanderCardState state, DateTime time) {
        if (getState() != state) {
            createSantanderCardStateTransition(state, time);
            setState(state);
            Signal.emit(STATE_CHANGED, this);
        }
        setLastUpdate(time);
    }

    public void createSantanderCardStateTransition(SantanderCardState state, DateTime date) {
        SantanderCardInfo cardInfo = getSantanderCardInfo();
        SantanderCardStateTransition transation = cardInfo.getLastTransition();
        DateTime lastTransactionTime = transation != null ? transation.getTransitionDate() : null;

        if (lastTransactionTime != null && lastTransactionTime.compareTo(date) >= 0) {
            new SantanderCardStateTransition(getSantanderCardInfo(), state, lastTransactionTime.plusSeconds(1));
        } else {
            new SantanderCardStateTransition(getSantanderCardInfo(), state, date);
        }

    }

    public static List<SantanderEntry> getSantanderEntryHistory(User user) {
        LinkedList<SantanderEntry> history = new LinkedList<>();

        for(SantanderEntry entry = user.getCurrentSantanderEntry(); entry != null; entry = entry.getPrevious()) {
            history.addFirst(entry);
        }

        return history;
    }

    @Deprecated
    public static List<SantanderCardInfo> getSantanderCardHistory(User user) {
        return getSantanderEntryHistory(user).stream()
                .filter(e -> e.getSantanderCardInfo() != null && e.getState() != SantanderCardState.IGNORED && e.getState() != SantanderCardState.PENDING)
                .sorted(REVERSE_COMPARATOR_BY_CREATED_DATE)
                .map(SantanderEntry::getSantanderCardInfo)
                .collect(Collectors.toList());
    }

    public boolean wasRegisterSuccessful() {
        return getState() != SantanderCardState.IGNORED && getState() != SantanderCardState.PENDING;
    }

    @Override
    public String getErrorDescription() {
        if (wasRegisterSuccessful()) {
            return "";
        } else {
            return super.getErrorDescription();
        }

    }

    public boolean isCardIssued() {
        SantanderCardState state = getState();
        return state == SantanderCardState.ISSUED || state == SantanderCardState.DELIVERED || state == SantanderCardState.EXPIRED;
    }

    public boolean canRegisterNew() {
        SantanderCardState state = getState();
        return state == SantanderCardState.IGNORED && getPrevious() == null;
    }
    
    public boolean canReemitCard() {
        SantanderCardState state = getState();
        SantanderEntry previous = getPrevious();
        return isCardIssued() || (state == SantanderCardState.IGNORED && previous != null && previous.isCardIssued());
    }

    public boolean canRenovateCard() {
        SantanderCardInfo cardInfo = getSantanderCardInfo();

        if (cardInfo == null) {
            return false;
        }

        DateTime expiryDate = getSantanderCardInfo().getExpiryDate();
        return canReemitCard() && expiryDate != null
                && Days.daysBetween(DateTime.now().withTimeAtStartOfDay(), expiryDate.withTimeAtStartOfDay()).getDays() <= 60;
    }

    public static boolean hasMifare(User user, String mifare) {
        if (Strings.isNullOrEmpty(mifare)) {
            return false;
        }

        return getSantanderCardHistory(user).stream()
                .anyMatch(e -> e.getMifareNumber() != null && e.getMifareNumber().equals(mifare));
    }
}
