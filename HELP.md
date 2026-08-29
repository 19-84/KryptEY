# Help

## How to start a chat?

- Invite a chat partner by clicking on the contact list and sending them your invite message. The
  invite message is placed in the text field of whatever app you are currently in.
- That means you choose the channel by choosing the app. To keep the invite away from the messenger
  entirely, open the other app first - a notes app, an email draft, a message to yourself - and tap
  the invite button while you are in it. Tap it while you are in the messenger and the messenger has
  it, whatever you do afterwards. This first key is the one moment a substituted key would not look
  wrong, because there is no earlier key for it to differ from.
- Your chat partner has to add you to their contact list and then send you an encrypted message.
  Copy this message to your clipboard and click on the "decrypt" button.
- A new context menu will open where you have to save the name of the chat partner. Then click on
  the "done" button. The contact is now automatically selected.

## Someone sent me an invite message. What do I have to do?

- Copy the invite message to your clipboard and click on the "decrypt" button.
- A new context menu will open where you have to save the name of the chat partner. Then click on
  the "done" button.
- Before you send anything private, compare the security number with them by voice or in person -
  see "How can I verify that my chat partner is who they claim to be?". The invite reached you
  through the messenger, and that comparison is the only step in this list that tells your chat
  partner apart from the messenger.
- Select your chat partner via the contact list and send them an encrypted message.

## How do I send/encrypt a message?

- If you have already added your chat partner as a contact, select them from the contact list and
  then write your message in the KryptEY text field.
- Then click on the "encrypt" button. The message will be placed encrypted in the text field of your
  messenger and you can send it.
- If you haven't added your chat partner yet, see "How to start a chat?"

## How do I receive/decrypt a message?

- Select your chat partner from the contact list and copy the message to your clipboard. Then click
  on the "decrypt" button.
- The message will be displayed in the KryptEY text field.

## How can I see past messages with my chat partner?

- Select your chat partner from the contact list and then click on the "messege log" button in the
  main view.
- Deleting the contact deletes the messages stored for them, and the app tells you if that could
  not be saved. Three things to know: it keeps that contact's key on purpose, so that a later
  invite claiming to be them cannot be accepted silently - deleting is not a way to start over with
  someone; a message from an old version of this app that could not be matched to any one contact
  stays in storage where no screen reaches it; and nothing here can remove what you already sent
  through the messenger.

## How can I verify that my chat partner is who they claim to be?

- Next to the name of your chat partner in the contact list is a verified/unverified symbol.
- Click on the symbol and your shared security number will appear. Compare the number with your chat
  partner's number.
- Compare it by voice - in person or on a call - and not by sending it through the messenger you are
  chatting in. A messenger able to change your keys is able to change the numbers you send each other
  so that they match, and then the comparison proves nothing. Reading them aloud is what makes it a
  check.
- If they match, click the "done" button and your contact will be marked as verified.
- If they do **not** match, use the reject button on that same screen - the one with the circle-X.
  It removes the stored key, so nothing is sent to it, and the app will not accept a replacement
  silently. Do not use it to back out of the screen: the return button is the one that leaves
  without changing anything.

## What are the text modes and how do I switch between them?

- There are two different modes in which you can send your encrypted messages.
- In Raw mode, the public keys and other information are displayed in their raw form. In Fairy Tale
  mode the same information is carried by invisible characters placed after a sentence from a fairy
  tale.
- Fairy Tale mode makes a message look unremarkable to somebody glancing at your screen. It does
  not conceal anything from the messenger: every Fairy Tale message ends in a run of invisible
  characters that nothing else produces, and the visible sentence is one the app ships. If your
  reason for choosing it is to stop the messenger knowing that you encrypt, it does not do that.
- In both modes, all messages are still encrypted and cannot be read by third parties - provided
  the key you pinned really belongs to your chat partner, which is what comparing the security
  number by voice establishes. Encryption on its own does not rule out the messenger, because the
  messenger is what delivered the key.
- You can switch between the modes by clicking on the raw/book symbol in the KryptEY text field on
  the left.

## The app says something could not be saved. What does that mean?

- It means the app could not write to its own storage - usually because the device has no free
  space, or because storage is locked and has not been unlocked since the phone started. Nothing
  about your keys or your chat partner has gone wrong.
- **Do not delete the contact and ask for a new invite.** That is the right answer to some problems
  and the wrong one here: swapping keys is the moment someone could substitute their own, so it is
  not worth doing over a storage error that will pass. Free up space, or unlock the device, and try
  the same thing again.
- If the message named a contact you had just added, the app will not encrypt to them until it can
  save them, because they would not be there after this keyboard restarts. Reading messages still
  works.
- If it said a deletion could not be saved, nothing was deleted - the contact, their key and their
  messages are all still here, and you can try again.
- If the app says it cannot open your stored contacts, it stops saving anything at all rather than
  replacing what it cannot read. Everything you do will report that it could not be saved until
  storage can be read again. That is deliberate: writing at that point would replace your contacts
  with an empty list.	
			