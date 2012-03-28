#Sciquest Attachments

At CSU we extend 
```org.kuali.kfs.module.purap.document.service.impl.B2BPurchaseOrderSciquestServiceImpl
with
```edu.csu.kfs.sciquest.impl.B2BPurchaseOrderServiceImpl
and override the getCxml method (line 151).

The main parts for attachments are on lines 395-422 and 499-529.
The first part specifies the attachments metadata (name, size, etc).
The second part encodes each attachments as binary data with Mime boundary lines.

On line 547 we have getNotesToSendToVendor which only allows notes/attachments marked with 'sendToVendor' as the value in the noteTopicText.

'noteTopicText' is set via RequisitionAttachmentTypeValuesFinder which is referenced in our
```edu/csu/kfs/module/purap/document/datadictionary/RequisitionDocument.xml.

Finally we added lines 89 to 93 to notes-sciquest.tag to allow the user to choose whether they want their note and attachment sent to sciquest.

##Disclaimer: The getCxml method is huge and could use some refactoring. I didn't write it but still felt dirty adding more to it.