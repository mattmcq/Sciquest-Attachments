/*
 * Copyright 2006-2007 The Kuali Foundation.
 * 
 * Licensed under the Educational Community License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.opensource.org/licenses/ecl1.php
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.csu.kfs.sciquest.impl;

import edu.csu.kfs.module.purap.CsuPurapConstants;
import org.apache.commons.lang.StringUtils;
import org.kuali.kfs.module.purap.PurapConstants;
import org.kuali.kfs.module.purap.businessobject.PurchaseOrderItem;
import org.kuali.kfs.module.purap.dataaccess.B2BDao;
import org.kuali.kfs.module.purap.document.PurchaseOrderDocument;
import org.kuali.kfs.module.purap.document.RequisitionDocument;
import org.kuali.kfs.module.purap.document.service.RequisitionService;
import org.kuali.kfs.module.purap.document.service.impl.B2BPurchaseOrderSciquestServiceImpl;
import org.kuali.kfs.module.purap.exception.B2BConnectionException;
import org.kuali.kfs.module.purap.exception.CxmlParseError;
import org.kuali.kfs.module.purap.util.PurApDateFormatUtils;
import org.kuali.kfs.module.purap.util.cxml.B2BParserHelper;
import org.kuali.kfs.module.purap.util.cxml.PurchaseOrderResponse;
import org.kuali.kfs.sys.context.SpringContext;
import org.kuali.kfs.vnd.VendorConstants;
import org.kuali.kfs.vnd.businessobject.ContractManager;
import org.kuali.kfs.vnd.businessobject.VendorAddress;
import org.kuali.kfs.vnd.document.service.VendorService;
import org.kuali.rice.kns.bo.Attachment;
import org.kuali.rice.kns.bo.Note;
import org.kuali.rice.kns.service.AttachmentService;
import org.kuali.rice.kns.service.DateTimeService;
import org.kuali.rice.kns.util.KualiDecimal;
import org.kuali.rice.kns.util.ObjectUtils;
import org.kuali.rice.kns.workflow.service.KualiWorkflowDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

public class B2BPurchaseOrderServiceImpl extends B2BPurchaseOrderSciquestServiceImpl {

    private static org.apache.log4j.Logger LOG = org.apache.log4j.Logger.getLogger(B2BPurchaseOrderSciquestServiceImpl.class);

    private RequisitionService requisitionService;
    private String b2bPunchoutURL;
    private String b2bPurchaseOrderPassword;
    private String b2bPurchaseOrderURL;
    private B2BDao b2bDao;
    private VendorService vendorService;

    private String defaultDistributionFaxNumber;

    private static final String NEWLINE = "\r\n";
    private static final String PREFIX = "--";

    @Override
    public String sendPurchaseOrder(PurchaseOrderDocument purchaseOrder) {
        // non-catalog POs might not have a vendor contract, so we need to get the contract manager from the PO which will always be there
        ContractManager contractManager = null;
        LOG.info("\n\npurchaseOrder.getRequisitionSourceCode() = " + purchaseOrder.getRequisitionSourceCode());
        LOG.info("purchaseOrder.getRequisitionIdentifier() = " + purchaseOrder.getRequisitionIdentifier());
        if (!PurapConstants.RequisitionSources.B2B.equals(purchaseOrder.getRequisitionSourceCode())) {
            contractManager = purchaseOrder.getContractManager();
        } else {
            contractManager = purchaseOrder.getVendorContract().getContractManager();
        }
        String contractManagerEmail = getContractManagerEmail(contractManager);
        String vendorDuns = purchaseOrder.getVendorDetail().getVendorDunsNumber();

        RequisitionDocument reqDocument = requisitionService.getRequisitionById(purchaseOrder.getRequisitionIdentifier());
        KualiWorkflowDocument reqWorkflowDoc = reqDocument.getDocumentHeader().getWorkflowDocument();

        LOG.info("sendPurchaseOrder(): punchoutUrl is " + b2bPunchoutURL);

        if (PurapConstants.RequisitionSources.B2B.equals(purchaseOrder.getRequisitionSourceCode())) {
            String validateErrors = verifyCxmlPOData(purchaseOrder, reqWorkflowDoc.getInitiatorNetworkId(), b2bPurchaseOrderPassword, contractManager, contractManagerEmail, vendorDuns);
            if (!StringUtils.isEmpty(validateErrors)) {
                return validateErrors;
            }
        }

        StringBuffer transmitErrors = new StringBuffer();
        try {
            LOG.info("sendPurchaseOrder() Generating cxml");
            String cxml = "";

            if (PurapConstants.RequisitionSources.B2B.equals(purchaseOrder.getRequisitionSourceCode())) {
                cxml = getCxml(purchaseOrder, reqWorkflowDoc.getInitiatorNetworkId(), b2bPurchaseOrderPassword, contractManager, contractManagerEmail, vendorDuns);
            } else {
                prepareNonB2BPurchaseOrderForTransmission(purchaseOrder);
                cxml = getCxml(purchaseOrder, reqWorkflowDoc.getInitiatorNetworkId(), b2bPurchaseOrderPassword, contractManager, contractManagerEmail, vendorDuns);
            }

            String responseCxml = b2bDao.sendPunchOutRequest(cxml, b2bPurchaseOrderURL);

            PurchaseOrderResponse poResponse = B2BParserHelper.getInstance().parsePurchaseOrderResponse(responseCxml);
            String statusText = poResponse.getStatusText();
            LOG.info("sendPurchaseOrder(): statusText is " + statusText);
            if (ObjectUtils.isNull(statusText) || (!"success".equalsIgnoreCase(statusText.trim()))) {
                LOG.error("sendPurchaseOrder(): PO cXML for po number " + purchaseOrder.getPurapDocumentIdentifier() + " failed sending to SciQuest:" + NEWLINE + statusText);
                transmitErrors.append("Unable to send Purchase Order: " + statusText);

                // find any additional error messages that might have been sent
                List errorMessages = poResponse.getPOResponseErrorMessages();
                if (ObjectUtils.isNotNull(errorMessages) && !errorMessages.isEmpty()) {
                    for (Iterator iter = errorMessages.iterator(); iter.hasNext(); ) {
                        String errorMessage = (String) iter.next();
                        if (ObjectUtils.isNotNull(errorMessage)) {
                            LOG.error("sendPurchaseOrder(): SciQuest error message for po number " + purchaseOrder.getPurapDocumentIdentifier() + ": " + errorMessage);
                            transmitErrors.append("Error sending Purchase Order: " + errorMessage);
                        }
                    }
                }
            }
        } catch (B2BConnectionException e) {
            LOG.error("sendPurchaseOrder() Error connecting to b2b", e);
            transmitErrors.append("Connection to Sciquest failed.");
        } catch (CxmlParseError e) {
            LOG.error("sendPurchaseOrder() Error Parsing", e);
            transmitErrors.append("Unable to read cxml returned from Sciquest.");
        } catch (Throwable e) {
            LOG.error("sendPurchaseOrder() Unknown Error", e);
            transmitErrors.append("Unexpected error occurred while attempting to transmit Purchase Order.");
        }

        return transmitErrors.toString();
    }

    /**
     * Generates the Sciquest XML for non-catelog Purchase Order documents
     *
     * @see org.kuali.kfs.module.purap.document.service.B2BPurchaseOrderService#getCxml(org.kuali.kfs.module.purap.document.PurchaseOrderDocument,
     *      org.kuali.rice.kim.bo.Person, String, org.kuali.kfs.vnd.businessobject.ContractManager,
     *      String, String)
     */
    @Override
    public String getCxml(PurchaseOrderDocument purchaseOrder, String requisitionInitiatorId, String password, ContractManager contractManager, String contractManagerEmail, String vendorDuns) {
        StringBuffer cxml = new StringBuffer();

        cxml.append(PREFIX + CsuPurapConstants.MIME_BOUNDARY_FOR_ATTACHMENTS + NEWLINE);
        cxml.append("Content-Type: application/xop+xml;" + NEWLINE);
        cxml.append("\tcharset=\"UTF-8\";" + NEWLINE);
        cxml.append("\ttype=\"text/xml\"" + NEWLINE);
        cxml.append("Content-Transfer-Encoding: 8bit" + NEWLINE);
        cxml.append("Content-ID: <4444711855555.4444160141700455555@sciquest.com>" + NEWLINE + NEWLINE);

        cxml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEWLINE);
        cxml.append("<!DOCTYPE PurchaseOrderMessage SYSTEM \"PO.dtd\">" + NEWLINE);
        cxml.append("<PurchaseOrderMessage version=\"2.0\">" + NEWLINE);
        cxml.append("  <Header>" + NEWLINE);

        // MessageId - can be whatever you would like it to be. Just make it unique.
        cxml.append("    <MessageId>KFS_cXML_PO</MessageId>" + NEWLINE);

        // Timestamp - it doesn't matter what's in the timezone, just that it's there (need "T" space between date/time)
        Date d = SpringContext.getBean(DateTimeService.class).getCurrentDate();
        SimpleDateFormat date = PurApDateFormatUtils.getSimpleDateFormat(PurapConstants.NamedDateFormats.CXML_SIMPLE_DATE_FORMAT);
        SimpleDateFormat time = PurApDateFormatUtils.getSimpleDateFormat(PurapConstants.NamedDateFormats.CXML_SIMPLE_TIME_FORMAT);
        cxml.append("    <Timestamp>").append(date.format(d)).append("T").append(time.format(d)).append("+05:30").append("</Timestamp>" + NEWLINE);

        cxml.append("    <Authentication>" + NEWLINE);
        cxml.append("      <Identity>ColoState</Identity>" + NEWLINE);
        cxml.append("      <SharedSecret>").append(password).append("</SharedSecret>" + NEWLINE);
        cxml.append("    </Authentication>" + NEWLINE);
        cxml.append("  </Header>" + NEWLINE);
        cxml.append("  <PurchaseOrder>" + NEWLINE);

        KualiWorkflowDocument workFlowDocument = purchaseOrder.getDocumentHeader().getWorkflowDocument();
        String documentType = workFlowDocument.getDocumentType();

        // void = VOPE      ammend = CGIN ?   ammend should =
        if (purchaseOrder.getStatusCode().equals("VOPE") || documentType.equalsIgnoreCase(PurapConstants.PurchaseOrderDocTypes.PURCHASE_ORDER_VOID_DOCUMENT)) {
            cxml.append("    <POHeader type=\"cancel\">" + NEWLINE);
            cxml.append("    <DistributeRevision>false</DistributeRevision>" + NEWLINE);
        } else if (documentType.equals(PurapConstants.PurchaseOrderDocTypes.PURCHASE_ORDER_AMENDMENT_DOCUMENT)) {
            cxml.append("    <POHeader type=\"update\">" + NEWLINE);
            cxml.append("    <DistributeRevision>false</DistributeRevision>" + NEWLINE);
        } else {
            cxml.append("    <POHeader>" + NEWLINE);
        }
        cxml.append("      <PONumber>").append(purchaseOrder.getPurapDocumentIdentifier()).append("</PONumber>" + NEWLINE);
        cxml.append("      <Requestor>" + NEWLINE);
        cxml.append("        <UserProfile username=\"").append(requisitionInitiatorId.toUpperCase()).append("\">" + NEWLINE);
        cxml.append("        </UserProfile>" + NEWLINE);
        cxml.append("      </Requestor>" + NEWLINE);
        cxml.append("      <Priority>High</Priority>" + NEWLINE);
        cxml.append("      <AccountingDate>").append(purchaseOrder.getPurchaseOrderCreateTimestamp()).append("</AccountingDate>" + NEWLINE);

        if (PurapConstants.RequisitionSources.B2B.equals(purchaseOrder.getRequisitionSourceCode())) {
            /** *** SUPPLIER SECTION **** */
            cxml.append("      <Supplier>" + NEWLINE);
            cxml.append("        <DUNS>").append(vendorDuns).append("</DUNS>" + NEWLINE);
            cxml.append("        <SupplierNumber>").append(purchaseOrder.getVendorNumber()).append("</SupplierNumber>" + NEWLINE);

            // Type attribute is required. Valid values: main and technical. Only main will be considered for POImport.
            cxml.append("        <ContactInfo type=\"main\">" + NEWLINE);
            // TelephoneNumber is required. With all fields, only numeric digits will be stored. Non-numeric characters are allowed, but
            // will be stripped before storing.
            cxml.append("          <Phone>" + NEWLINE);
            cxml.append("            <TelephoneNumber>" + NEWLINE);
            cxml.append("              <CountryCode>1</CountryCode>" + NEWLINE);
            if (contractManager.getContractManagerPhoneNumber().length() > 4) {
                cxml.append("              <AreaCode>").append(contractManager.getContractManagerPhoneNumber().substring(0, 3)).append("</AreaCode>" + NEWLINE);
                cxml.append("              <Number>").append(contractManager.getContractManagerPhoneNumber().substring(3)).append("</Number>" + NEWLINE);
            } else {
                LOG.error("getCxml() The phone number is invalid for this contract manager: " + contractManager.getContractManagerUserIdentifier() + " " + contractManager.getContractManagerName());
                cxml.append("              <AreaCode>555</AreaCode>" + NEWLINE);
                cxml.append("              <Number>").append(contractManager.getContractManagerPhoneNumber()).append("</Number>" + NEWLINE);
            }
            cxml.append("            </TelephoneNumber>" + NEWLINE);
            cxml.append("          </Phone>" + NEWLINE);
            cxml.append("        </ContactInfo>" + NEWLINE);
            cxml.append("      </Supplier>" + NEWLINE);
        } else {

            /** *** SUPPLIER SECTION **** */
            cxml.append("      <Supplier>" + NEWLINE);
            cxml.append("        <Name><![CDATA[").append(purchaseOrder.getVendorName()).append("]]></Name>" + NEWLINE);

            // Type attribute is required. Valid values: main and technical. Only main will be considered for POImport.
            cxml.append("        <ContactInfo type=\"main\">" + NEWLINE);
            // TelephoneNumber is required. With all fields, only numeric digits will be stored. Non-numeric characters are allowed, but
            // will be stripped before storing.
            cxml.append("          <Phone>" + NEWLINE);
            cxml.append("            <TelephoneNumber>" + NEWLINE);
            cxml.append("              <CountryCode>1</CountryCode>" + NEWLINE);
            if (StringUtils.isNotEmpty(purchaseOrder.getVendorPhoneNumber()) && purchaseOrder.getVendorPhoneNumber().length() > 4) {
                cxml.append("              <AreaCode>").append(purchaseOrder.getVendorPhoneNumber().substring(0, 3)).append("</AreaCode>" + NEWLINE);
                cxml.append("              <Number>").append(purchaseOrder.getVendorPhoneNumber().substring(3)).append("</Number>" + NEWLINE);
            } else {

                cxml.append("              <AreaCode>000</AreaCode>" + NEWLINE);
                cxml.append("              <Number>-000-0000</Number>" + NEWLINE);
            }

            cxml.append("            </TelephoneNumber>" + NEWLINE);
            cxml.append("          </Phone>" + NEWLINE);

            cxml.append("          <ContactAddress>" + NEWLINE);
            if (StringUtils.isNotEmpty(purchaseOrder.getVendorLine1Address())) {
                cxml.append("          <AddressLine label=\"Street1\" linenumber=\"1\"><![CDATA[").append(purchaseOrder.getVendorLine1Address()).append("]]></AddressLine>" + NEWLINE);
            }
            if (StringUtils.isNotEmpty(purchaseOrder.getVendorLine2Address())) {
                cxml.append("          <AddressLine label=\"Street2\" linenumber=\"2\"><![CDATA[").append(purchaseOrder.getVendorLine2Address()).append("]]></AddressLine>" + NEWLINE);
            }
            if (StringUtils.isNotEmpty(purchaseOrder.getVendorStateCode())) {
                cxml.append("          <State>").append(purchaseOrder.getBillingStateCode()).append("</State>" + NEWLINE);
            }
            if (StringUtils.isNotEmpty(purchaseOrder.getVendorPostalCode())) {
                cxml.append("          <PostalCode>").append(purchaseOrder.getBillingPostalCode()).append("</PostalCode>" + NEWLINE);
            }
            if (StringUtils.isNotEmpty(purchaseOrder.getVendorCountryCode())) {
                cxml.append("          <Country isocountrycode=\"").append(purchaseOrder.getBillingCountryCode()).append("\">").append(purchaseOrder.getBillingCountryCode()).append("</Country>" + NEWLINE);
            }
            cxml.append("          </ContactAddress>" + NEWLINE);

            cxml.append("        </ContactInfo>" + NEWLINE);
            cxml.append("      </Supplier>" + NEWLINE);

            /** *** DISTRIBUTION SECTION *** */
            VendorAddress vendorAddress = vendorService.getVendorDefaultAddress(purchaseOrder.getVendorDetail().getVendorAddresses(), VendorConstants.AddressTypes.PURCHASE_ORDER, purchaseOrder.getDeliveryCampusCode());
            cxml.append("      <OrderDistribution>" + NEWLINE);

            // first take fax from PO, if empty then get fax number for PO default vendor address
            String vendorFaxNumber = purchaseOrder.getVendorFaxNumber();
            if (StringUtils.isBlank(vendorFaxNumber) && vendorAddress != null) {
                vendorFaxNumber = vendorAddress.getVendorFaxNumber();
            }

            // use fax number if not blank, else use vendor email
            if (StringUtils.isNotBlank(vendorFaxNumber) && vendorFaxNumber.length() > 4) {
                cxml.append("        <DistributionMethod type=\"fax\">" + NEWLINE);
                cxml.append("          <Fax>" + NEWLINE);
                cxml.append("            <TelephoneNumber>" + NEWLINE);
                cxml.append("              <CountryCode>1</CountryCode>" + NEWLINE);
                cxml.append("              <AreaCode>").append(vendorFaxNumber.substring(0, 3)).append("</AreaCode>" + NEWLINE);
                cxml.append("              <Number>").append(vendorFaxNumber.substring(3)).append("</Number>" + NEWLINE);
                cxml.append("            </TelephoneNumber>" + NEWLINE);
                cxml.append("          </Fax>" + NEWLINE);
            } else {
                String emailAddress = "";
                if (vendorAddress != null) {
                    emailAddress = vendorAddress.getVendorAddressEmailAddress();
                }

                if (StringUtils.isNotBlank(emailAddress)) {
                    cxml.append("        <DistributionMethod type=\"email\">" + NEWLINE);
                    cxml.append("          <Email><![CDATA[").append(emailAddress).append("]]></Email>" + NEWLINE);
                } else {
                    // default fax
                    cxml.append("        <DistributionMethod type=\"fax\">" + NEWLINE);
                    cxml.append("          <Fax>" + NEWLINE);
                    cxml.append("            <TelephoneNumber>" + NEWLINE);
                    cxml.append("              <CountryCode>1</CountryCode>" + NEWLINE);
                    cxml.append("              <AreaCode>").append(defaultDistributionFaxNumber.substring(0, 3)).append("</AreaCode>" + NEWLINE);
                    cxml.append("              <Number>").append(defaultDistributionFaxNumber.substring(3)).append("</Number>" + NEWLINE);
                    cxml.append("            </TelephoneNumber>" + NEWLINE);
                    cxml.append("          </Fax>" + NEWLINE);
                }
            }
            cxml.append("        </DistributionMethod>" + NEWLINE);
            cxml.append("      </OrderDistribution>" + NEWLINE);
        }

        /** *** BILL TO SECTION **** */
        cxml.append("      <BillTo>" + NEWLINE);
        cxml.append("        <Address>" + NEWLINE);
        cxml.append("          <TemplateName>Bill To</TemplateName>" + NEWLINE);
        cxml.append("          <AddressCode>").append(purchaseOrder.getDeliveryCampusCode()).append("</AddressCode>" + NEWLINE);
        // Contact - There can be 0-5 Contact elements. The label attribute is optional.
        cxml.append("          <Contact label=\"FirstName\" linenumber=\"1\"><![CDATA[Accounts]]></Contact>" + NEWLINE);
        cxml.append("          <Contact label=\"LastName\" linenumber=\"2\"><![CDATA[Payable]]></Contact>" + NEWLINE);
        cxml.append("          <Contact label=\"Company\" linenumber=\"3\"><![CDATA[").append(purchaseOrder.getBillingName().trim()).append("]]></Contact>" + NEWLINE);
        cxml.append("          <Contact label=\"Phone\" linenumber=\"4\"><![CDATA[").append(purchaseOrder.getBillingPhoneNumber().trim()).append("]]></Contact>" + NEWLINE);
        // There must be 1-5 AddressLine elements. The label attribute is optional.
        cxml.append("          <AddressLine label=\"Street1\" linenumber=\"1\"><![CDATA[").append(purchaseOrder.getBillingLine1Address()).append("]]></AddressLine>" + NEWLINE);
        cxml.append("          <AddressLine label=\"Street2\" linenumber=\"2\"><![CDATA[").append(purchaseOrder.getBillingLine2Address()).append("]]></AddressLine>" + NEWLINE);
        cxml.append("          <City><![CDATA[").append(purchaseOrder.getBillingCityName()).append("]]></City>" + NEWLINE); // Required.
        cxml.append("          <State>").append(purchaseOrder.getBillingStateCode()).append("</State>" + NEWLINE);
        cxml.append("          <PostalCode>").append(purchaseOrder.getBillingPostalCode()).append("</PostalCode>" + NEWLINE); // Required.
        cxml.append("          <Country isocountrycode=\"").append(purchaseOrder.getBillingCountryCode()).append("\">").append(purchaseOrder.getBillingCountryCode()).append("</Country>" + NEWLINE);
        cxml.append("        </Address>" + NEWLINE);
        cxml.append("      </BillTo>" + NEWLINE);

        /** *** SHIP TO SECTION **** */
        cxml.append("      <ShipTo>" + NEWLINE);
        cxml.append("        <Address>" + NEWLINE);
        cxml.append("          <TemplateName>Ship To</TemplateName>" + NEWLINE);
        // AddressCode. A code to identify the address, that is sent to the supplier.
        cxml.append("          <AddressCode>").append(purchaseOrder.getReceivingName().trim()).append("</AddressCode>" + NEWLINE);
        cxml.append("          <Contact label=\"Name\" linenumber=\"1\"><![CDATA[").append(purchaseOrder.getDeliveryToName().trim()).append("]]></Contact>" + NEWLINE);
        cxml.append("          <Contact label=\"PurchasingEmail\" linenumber=\"2\"><![CDATA[").append(contractManagerEmail).append("]]></Contact>" + NEWLINE);
        if (ObjectUtils.isNotNull(purchaseOrder.getInstitutionContactEmailAddress())) {
            cxml.append("          <Contact label=\"ContactEmail\" linenumber=\"3\"><![CDATA[").append(purchaseOrder.getInstitutionContactEmailAddress()).append("]]></Contact>" + NEWLINE);
        } else {
            cxml.append("          <Contact label=\"ContactEmail\" linenumber=\"3\"><![CDATA[").append(purchaseOrder.getRequestorPersonEmailAddress()).append("]]></Contact>" + NEWLINE);
        }
        if (ObjectUtils.isNotNull(purchaseOrder.getInstitutionContactPhoneNumber())) {
            cxml.append("          <Contact label=\"Phone\" linenumber=\"4\"><![CDATA[").append(purchaseOrder.getInstitutionContactPhoneNumber().trim()).append("]]></Contact>" + NEWLINE);
        } else {
            cxml.append("          <Contact label=\"Phone\" linenumber=\"4\"><![CDATA[").append(purchaseOrder.getRequestorPersonPhoneNumber()).append("]]></Contact>" + NEWLINE);
        }

        //check indicator to decide if receiving or delivery address should be sent to the vendor
        if (purchaseOrder.getAddressToVendorIndicator()) {  //use receiving address
            if (StringUtils.isNotEmpty(purchaseOrder.getDeliveryBuildingName())) {
                cxml.append("          <Contact label=\"Building\" linenumber=\"5\"><![CDATA[").append(purchaseOrder.getDeliveryBuildingName()).append(" - Rm ").append(purchaseOrder.getDeliveryBuildingRoomNumber()).append("]]></Contact>" + NEWLINE);
            }
            cxml.append("          <AddressLine label=\"Street1\" linenumber=\"1\"><![CDATA[").append(purchaseOrder.getReceivingName().trim()).append("]]></AddressLine>" + NEWLINE);
            cxml.append("          <AddressLine label=\"Street2\" linenumber=\"2\"><![CDATA[").append(purchaseOrder.getReceivingLine1Address().trim()).append("]]></AddressLine>" + NEWLINE);
            if (ObjectUtils.isNull(purchaseOrder.getReceivingLine2Address())) {
                cxml.append("          <AddressLine label=\"Street3\" linenumber=\"3\"><![CDATA[").append(" ").append("]]></AddressLine>" + NEWLINE);
            } else {
                cxml.append("          <AddressLine label=\"Street3\" linenumber=\"3\"><![CDATA[").append(purchaseOrder.getReceivingLine2Address()).append("]]></AddressLine>" + NEWLINE);
            }
            cxml.append("          <City><![CDATA[").append(purchaseOrder.getReceivingCityName().trim()).append("]]></City>" + NEWLINE);
            cxml.append("          <State>").append(purchaseOrder.getReceivingStateCode()).append("</State>" + NEWLINE);
            cxml.append("          <PostalCode>").append(purchaseOrder.getReceivingPostalCode()).append("</PostalCode>" + NEWLINE);
            cxml.append("          <Country isocountrycode=\"").append(purchaseOrder.getReceivingCountryCode()).append("\">").append(purchaseOrder.getReceivingCountryCode()).append("</Country>" + NEWLINE);
        } else {
            if (StringUtils.isNotEmpty(purchaseOrder.getDeliveryBuildingName())) {
                cxml.append("          <Contact label=\"Building\" linenumber=\"5\"><![CDATA[").append(purchaseOrder.getDeliveryBuildingName()).append(" - Rm ").append(purchaseOrder.getDeliveryBuildingRoomNumber()).append("]]></Contact>" + NEWLINE);
            }
            cxml.append("          <AddressLine label=\"Street1\" linenumber=\"1\"><![CDATA[").append(purchaseOrder.getDeliveryBuildingLine1Address().trim()).append("]]></AddressLine>" + NEWLINE);
            cxml.append("          <AddressLine label=\"Street2\" linenumber=\"2\"><![CDATA[Room #").append(purchaseOrder.getDeliveryBuildingRoomNumber().trim()).append("]]></AddressLine>" + NEWLINE);
            cxml.append("          <AddressLine label=\"Company\" linenumber=\"4\"><![CDATA[").append(purchaseOrder.getBillingName().trim()).append("]]></AddressLine>" + NEWLINE);
            if (ObjectUtils.isNull(purchaseOrder.getDeliveryBuildingLine2Address())) {
                cxml.append("          <AddressLine label=\"Street3\" linenumber=\"3\"><![CDATA[").append(" ").append("]]></AddressLine>" + NEWLINE);
            } else {
                cxml.append("          <AddressLine label=\"Street3\" linenumber=\"3\"><![CDATA[").append(purchaseOrder.getDeliveryBuildingLine2Address()).append("]]></AddressLine>" + NEWLINE);
            }
            cxml.append("          <City><![CDATA[").append(purchaseOrder.getDeliveryCityName().trim()).append("]]></City>" + NEWLINE);
            cxml.append("          <State>").append(purchaseOrder.getDeliveryStateCode()).append("</State>" + NEWLINE);
            cxml.append("          <PostalCode>").append(purchaseOrder.getDeliveryPostalCode()).append("</PostalCode>" + NEWLINE);
            cxml.append("          <Country isocountrycode=\"").append(purchaseOrder.getDeliveryCountryCode()).append("\">").append(purchaseOrder.getDeliveryCountryCode()).append("</Country>" + NEWLINE);
        }
        cxml.append("        </Address>" + NEWLINE);
        cxml.append("      </ShipTo>" + NEWLINE);

        // mjmc - Attachments must be defined in the xml part and must match info in MIME binary part
        List<Note> notesToSendToVendor = getNotesToSendToVendor(purchaseOrder);
        if (!notesToSendToVendor.isEmpty()) {
            String allNotes = "";
            String allNotesNoAttach = "";
            cxml.append("      <ExternalInfo >" + NEWLINE);
            cxml.append("        <Attachments xmlns:xop = \"http://www.w3.org/2004/08/xop/include/\" >" + NEWLINE);

            for (int i = 0; i < notesToSendToVendor.size(); i++) {
                Note note = notesToSendToVendor.get(i);
                Attachment attachment = SpringContext.getBean(AttachmentService.class).getAttachmentByNoteId(note.getNoteIdentifier());
                if (ObjectUtils.isNotNull(attachment)) {
                    allNotes = allNotes + NEWLINE + "(" + (i + 1) + ") " + note.getNoteText() + "  ";
                    cxml.append("          <Attachment id=\"" + attachment.getAttachmentIdentifier() + "\" type=\"file\">" + NEWLINE);
                    cxml.append("            <AttachmentName><![CDATA[" + attachment.getAttachmentFileName() + "]]></AttachmentName>" + NEWLINE);
                    cxml.append("            <AttachmentURL>http://usertest.sciquest.com/apps/Router/ReqAttachmentDownload?AttachmentId=" + attachment.getAttachmentIdentifier() +
                            "&amp;DocId=" + purchaseOrder.getPurapDocumentIdentifier() +
                            "&amp;OrgName=SQSupportTest&amp;AuthMethod=Local</AttachmentURL>" + NEWLINE);
                    cxml.append("            <AttachmentSize>" + attachment.getAttachmentFileSize() / 1024 + "</AttachmentSize>" + NEWLINE);
                    cxml.append("            <xop:Include href=\"cid:" + attachment.getAttachmentIdentifier() + "@sciquest.com\" />" + NEWLINE);
                    cxml.append("          </Attachment>" + NEWLINE);
                } else {
                    allNotesNoAttach = allNotesNoAttach + "          � " + note.getNoteText() + "          ";
                }
            }
            cxml.append("        </Attachments>" + NEWLINE);
            cxml.append("          <Note><![CDATA[" + allNotesNoAttach + "          " + allNotes + "]]></Note>" + NEWLINE);
            cxml.append("      </ExternalInfo>" + NEWLINE);
        } // mjmc done adding attachments

        cxml.append("      <CustomFieldValueSet name=\"SupplierAddress1\">" + NEWLINE);
        cxml.append("        <CustomFieldValue>" + NEWLINE);
        cxml.append("          <Value><![CDATA[").append(purchaseOrder.getVendorLine1Address()).append("]]></Value>" + NEWLINE);
        cxml.append("         </CustomFieldValue>" + NEWLINE);
        cxml.append("      </CustomFieldValueSet>" + NEWLINE);
        cxml.append("      <CustomFieldValueSet name=\"SupplierCityStateZip\">" + NEWLINE);
        cxml.append("        <CustomFieldValue>" + NEWLINE);
        cxml.append("          <Value><![CDATA[").append(purchaseOrder.getVendorCityName()).append(", ").append(purchaseOrder.getVendorStateCode()).append(" ").append(purchaseOrder.getVendorPostalCode()).append("]]></Value>" + NEWLINE);
        cxml.append("         </CustomFieldValue>" + NEWLINE);
        cxml.append("      </CustomFieldValueSet>" + NEWLINE);
        cxml.append("    </POHeader>" + NEWLINE);

        /** *** Items Section **** */
        List detailList = purchaseOrder.getItems();

        for (Iterator iter = detailList.iterator(); iter.hasNext(); ) {
            PurchaseOrderItem poi = (PurchaseOrderItem) iter.next();
            if ((ObjectUtils.isNotNull(poi.getItemType())) && poi.getItemType().isLineItemIndicator()) {
                String uom = poi.getItemUnitOfMeasureCode();
                KualiDecimal quantity = poi.getItemQuantity();
                if (quantity == null || quantity.isZero()) {
                    quantity = new KualiDecimal(1);
                    uom = "LOT";
                }

                cxml.append("    <POLine linenumber=\"").append(poi.getItemLineNumber()).append("\">" + NEWLINE);
                cxml.append("      <Item>" + NEWLINE);
                // CatalogNumber - This is a string that the supplier uses to identify the item (i.e., SKU). Optional.
                cxml.append("        <CatalogNumber><![CDATA[").append(poi.getItemCatalogNumber()).append("]]></CatalogNumber>" + NEWLINE);
                if (ObjectUtils.isNotNull(poi.getItemAuxiliaryPartIdentifier())) {
                    cxml.append("        <AuxiliaryCatalogNumber><![CDATA[").append(poi.getItemAuxiliaryPartIdentifier()).append("]]></AuxiliaryCatalogNumber>" + NEWLINE);
                }
                cxml.append("        <Description><![CDATA[").append(StringUtils.substring(poi.getItemDescription(), 0, 254)).append("]]></Description>" + NEWLINE); // Required.
                cxml.append("        <ProductUnitOfMeasure type=\"supplier\"><Measurement><MeasurementValue><![CDATA[").append(uom).append("]]></MeasurementValue></Measurement></ProductUnitOfMeasure>" + NEWLINE);
                cxml.append("        <ProductUnitOfMeasure type=\"system\"><Measurement><MeasurementValue><![CDATA[").append(uom).append("]]></MeasurementValue></Measurement></ProductUnitOfMeasure>" + NEWLINE);

                if (PurapConstants.RequisitionSources.B2B.equals(purchaseOrder.getRequisitionSourceCode())) {

                    // ProductReferenceNumber - Unique id for hosted products in SelectSite
                    if (poi.getExternalOrganizationB2bProductTypeName().equals("Punchout")) {
                        cxml.append("        <ProductReferenceNumber>null</ProductReferenceNumber>\n");
                    } else {
                        cxml.append("        <ProductReferenceNumber>").append(poi.getExternalOrganizationB2bProductReferenceNumber()).append("</ProductReferenceNumber>\n");
                    }
                    // ProductType - Describes the type of the product or service. Valid values: Catalog, Form, Punchout. Mandatory.
                    cxml.append("        <ProductType>").append(poi.getExternalOrganizationB2bProductTypeName()).append("</ProductType>\n");

                } else {

                    cxml.append("        <ProductReferenceNumber>null</ProductReferenceNumber>" + NEWLINE);
                    // non-catalog POs don't have product type so we send 'Form'
                    cxml.append("        <ProductType>Form</ProductType>" + NEWLINE);
                }
                cxml.append("      </Item>" + NEWLINE);
                cxml.append("      <Quantity>").append(quantity).append("</Quantity>" + NEWLINE);
                // LineCharges - All the monetary charges for this line, including the price, tax, shipping, and handling.
                // Required.
                cxml.append("      <LineCharges>" + NEWLINE);
                cxml.append("        <UnitPrice>" + NEWLINE);
                cxml.append("          <Money currency=\"USD\">").append(poi.getItemUnitPrice()).append("</Money>" + NEWLINE);
                cxml.append("        </UnitPrice>" + NEWLINE);
                cxml.append("      </LineCharges>" + NEWLINE);
                cxml.append("    </POLine>" + NEWLINE);
            }

        }

        cxml.append("  </PurchaseOrder>" + NEWLINE);
        cxml.append("</PurchaseOrderMessage>" + NEWLINE);

        LOG.info("getCxml(): cXML for po number " + purchaseOrder.getPurapDocumentIdentifier() + ":" + NEWLINE + cxml.toString());

        // mjmc *****************************************************************************************************************
        // mjmc * This is where the attachment gets put into the xml as raw binary data                                         *
        // mjmc *****************************************************************************************************************
        if (!notesToSendToVendor.isEmpty()) {
            for (int i = 0; i < notesToSendToVendor.size(); i++) {
                Note note = notesToSendToVendor.get(i);
                //Attachment poAttachment = note.getAttachment(); // mjmc - doesn't work as of 2010-01
                try {
                    Attachment poAttachment = SpringContext.getBean(AttachmentService.class).getAttachmentByNoteId(note.getNoteIdentifier());
                    if (ObjectUtils.isNotNull(poAttachment)) {
                        cxml.append(PREFIX + CsuPurapConstants.MIME_BOUNDARY_FOR_ATTACHMENTS + NEWLINE);
                        cxml.append("Content-Type: application/octet-stream" + NEWLINE);
                        cxml.append("Content-Transfer-Encoding: binary" + NEWLINE);
                        cxml.append("Content-ID: <" + poAttachment.getAttachmentIdentifier() + "@sciquest.com>" + NEWLINE);
                        cxml.append("Content-Disposition: attachment; filename=\"" + poAttachment.getAttachmentFileName() + "\"" + NEWLINE + NEWLINE);

                        InputStream attInputStream = poAttachment.getAttachmentContents();
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        int c;
                        while ((c = attInputStream.read()) != -1) buffer.write(c);
                        String binaryStream = new String(buffer.toByteArray());

                        cxml.append(binaryStream + NEWLINE);
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
            cxml.append(PREFIX + CsuPurapConstants.MIME_BOUNDARY_FOR_ATTACHMENTS + PREFIX + NEWLINE);  // signals this is the last MIME boundary
        } else {
            cxml.append(NEWLINE + NEWLINE + PREFIX + CsuPurapConstants.MIME_BOUNDARY_FOR_ATTACHMENTS + PREFIX + NEWLINE);
        }

// mjmc - for testing :
//        FileOutputStream out;
//        try {
//            LOG.info("storing copy of po xml before sending it to sciquest: /tmp/po-" + purchaseOrder.getPurapDocumentIdentifier() + ".xml");
//            out = new FileOutputStream("/tmp/po-" + purchaseOrder.getPurapDocumentIdentifier() + ".xml");
//            out.write(cxml.toString().getBytes());           // used for testing with cURL
//        } catch (IOException e) {
//            LOG.error(e);
//        }

        return cxml.toString();
    }

    /**
     * Returns list of Note(s) that should be sent to the vendor
     */
    private List<Note> getNotesToSendToVendor(PurchaseOrderDocument purchaseOrder) {
        List notesToSend = new ArrayList();
        for (int i = 0; i < purchaseOrder.getBoNotes().size(); i++) {
            Note note = (Note) purchaseOrder.getBoNotes().get(i);
            if (StringUtils.equalsIgnoreCase(note.getNoteTopicText(), "sendToVendor")) {
                notesToSend.add(note);
            }
        }
        return notesToSend;
    }

    /**
     * Sets fields on the purchase order document that the are expected for the xml but might not be populated
     * on non B2B documents (to prevent NPEs)
     *
     * @param purchaseOrder document instance to prepare
     */
    protected void prepareNonB2BPurchaseOrderForTransmission(PurchaseOrderDocument purchaseOrder) {
        List detailList = purchaseOrder.getItems();
        for (Iterator iter = detailList.iterator(); iter.hasNext(); ) {
            PurchaseOrderItem poi = (PurchaseOrderItem) iter.next();

            if (poi.getItemCatalogNumber() == null) {
                poi.setItemCatalogNumber("");
            }
            if (poi.getExternalOrganizationB2bProductTypeName() == null) {
                poi.setExternalOrganizationB2bProductTypeName("");
            }
            if (poi.getExternalOrganizationB2bProductReferenceNumber() == null) {
                poi.setExternalOrganizationB2bProductReferenceNumber("");
            }
            if (poi.getExternalOrganizationB2bProductTypeName() == null) {
                poi.setExternalOrganizationB2bProductTypeName("");
            }
        }
    }

    public void setRequisitionService(RequisitionService requisitionService) {
        super.setRequisitionService(requisitionService);
        this.requisitionService = requisitionService;
    }

    public void setB2bDao(B2BDao b2bDao) {
        super.setB2bDao(b2bDao);
        this.b2bDao = b2bDao;
    }

    public void setB2bPunchoutURL(String b2bPunchoutURL) {
        super.setB2bPunchoutURL(b2bPunchoutURL);
        this.b2bPunchoutURL = b2bPunchoutURL;
    }

    public void setB2bPurchaseOrderURL(String b2bPurchaseOrderURL) {
        super.setB2bPurchaseOrderURL(b2bPurchaseOrderURL);
        this.b2bPurchaseOrderURL = b2bPurchaseOrderURL;
    }

    public void setB2bPurchaseOrderPassword(String b2bPurchaseOrderPassword) {
        super.setB2bPurchaseOrderPassword(b2bPurchaseOrderPassword);
        this.b2bPurchaseOrderPassword = b2bPurchaseOrderPassword;
    }

    public void setVendorService(VendorService vendorService) {
        this.vendorService = vendorService;
    }

    public void setDefaultDistributionFaxNumber(String defaultDistributionFaxNumber) {
        this.defaultDistributionFaxNumber = defaultDistributionFaxNumber;
    }

}
