package xmpp.client.service.user;

import java.util.Collection;

import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.RosterListener;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.util.StringUtils;

import xmpp.client.service.Service;
import xmpp.client.service.user.contact.Contact;
import xmpp.client.service.user.contact.ContactList;
import xmpp.client.service.user.group.GroupList;
import android.util.Log;

public class UserService implements RosterListener {
	private static final String TAG = UserService.class.getName();
	private UserList mUserList;
	private ContactList mContactList;
	private Roster mRoster;
	private final Service mService;
	private User mUserMe;

	public UserService(Roster roster, Service service, User userMe) {
		mService = service;
		mUserMe = userMe;
		mRoster = roster;
		mRoster.addRosterListener(this);
		mUserList = new UserList();
		mContactList = new ContactList();
		buildUserList();
	}

	public User addUser(String uid) {
		return addUser(uid, uid);
	}

	public User addUser(String uid, String name) {
		return addUser(uid, name, new String[] { getGroups().get(0) });
	}

	public User addUser(String uid, String name, String group) {
		return addUser(uid, name, new String[] { group });
	}

	public User addUser(String uid, String name, String[] groups) {
		try {
			mRoster.createEntry(uid, name, groups);
		} catch (final Exception e) {
			Log.i(TAG, "addUser", e);
			return null;
		}
		return getUser(uid, true);
	}

	public User addUser(String uid, String[] groups) {
		return addUser(uid, uid, groups);
	}

	public void buildUserList() {
		final Collection<RosterEntry> roster = mRoster.getEntries();
		for (final RosterEntry rosterEntry : roster) {
			getUser(rosterEntry.getUser(), true);
		}
		transportCheck();
	}

	public void destroy() {
		mUserList.clear();
		mUserList = null;
		mContactList.clear();
		mContactList = null;
		mRoster.removeRosterListener(this);
		mRoster = null;
		mUserMe = null;
	}

	@Override
	public void entriesAdded(Collection<String> addresses) {
		for (final String uid : addresses) {
			getUser(uid, true);
		}
	}

	@Override
	public void entriesDeleted(Collection<String> addresses) {
		for (final String uid : addresses) {
			final User user = getUser(uid, false);
			mUserList.remove(user);
			mContactList.removeUser(uid);
			mService.sendRosterDeleted(uid);
		}
	}

	@Override
	public void entriesUpdated(Collection<String> addresses) {
		for (final String uid : addresses) {
			final User user = getUser(uid, true);
			final RosterEntry re = mRoster.getEntry(uid);
			user.setUserName(re.getName());
			user.setGroups(new GroupList(re.getGroups()));
			mService.sendRosterUpdated(user);
		}
	}

	public Contact getContact(String uid, boolean addIfNotExists) {
		uid = StringUtils.parseBareAddress(uid);
		if (!mContactList.contains(uid)) {
			if (addIfNotExists) {
				getUser(uid, true);
			} else {
				return new Contact(getUser(uid, false));
			}
		}
		return mContactList.get(uid);
	}

	public Contact getContact(User user, boolean addIfNotExists) {
		return getContact(user.getUserLogin(), addIfNotExists);
	}

	public ContactList getContactList() {
		return new ContactList(mUserList);
	}

	GroupList getGroups() {
		return new GroupList(mRoster.getGroups());
	}

	public User getUser(String uid, boolean addIfNotExists) {
		return getUser(uid, addIfNotExists, true);
	}

	public User getUser(String uid, boolean addIfNotExists,
			boolean setupIfNotExists) {
		if (mUserMe.getUserLogin().equalsIgnoreCase(
				StringUtils.parseBareAddress(uid))) {
			return mUserMe;
		}
		for (int i = 0; i < mUserList.size(); i++) {
			final User user = mUserList.get(i);
			if (user != null && user.getFullUserLogin().equalsIgnoreCase(uid)) {
				return user;
			}
		}
		for (int i = 0; i < mUserList.size(); i++) {
			final User user = mUserList.get(i);
			if (user != null
					&& user.getUserLogin().equalsIgnoreCase(
							StringUtils.parseBareAddress(uid))) {
				return user;
			}
		}
		if (setupIfNotExists) {
			return setupUser(uid, addIfNotExists);
		} else {
			return null;
		}
	}

	UserList getUserList() {
		return mUserList;
	}

	public User getUserMe() {
		return mUserMe;
	}

	@Override
	public void presenceChanged(Presence presence) {
		final User user = getUser(presence.getFrom(), true, false);
		if (user == null) {
			return;
		}
		user.setUserState(new UserState(presence));
		user.setRessource(StringUtils.parseResource(presence.getFrom()));
		mService.sendRosterUpdated(user);
	}

	public User setupUser(String uid, boolean addIfNotExists) {
		final RosterEntry re = mRoster.getEntry(StringUtils
				.parseBareAddress(uid));
		if (re != null) {
			final User user = new User(re, mRoster.getPresence(re.getUser()));
			if (addIfNotExists) {
				setupUser(user);
			}
			return user;
		} else {
			Log.w(TAG, "creating Invalid user for: " + uid);
			final User u = new User();
			u.setUserLogin(uid);
			u.setUserState(UserState.Invalid);
			return u;
		}
	}

	public User setupUser(User user) {
		final User user2 = getUser(user.getFullUserLogin(), false, false);
		if (user2 == null || user2.getUserState() == UserState.Invalid) {
			if (user2 != null) {
				mUserList.remove(user2);
				mContactList.removeUser(user2.getFullUserLogin());
				mService.sendRosterDeleted(user2.getFullUserLogin());
			}
		}
		mUserList.add(user);
		mContactList.add(user);
		mService.sendRosterAdded(user);
		return user2;
	}

	public void setUserMe(User user) {
		mUserMe = user;
	}

	void transportCheck() {
		final UserList transportList = new UserList();
		for (final User user : mUserList) {
			if (user.getTransportState() == User.TSTATE_IS_TRANSPORT) {
				transportList.add(user);
			}
		}

		for (final User user : mUserList) {
			if (!transportList.contains(user)) {
				for (final User transport : transportList) {
					user.transportCheck(transport);
				}
			}
		}
	}

	public void updateUser(User user) {
		final User u = getUser(user.getUserLogin(), true);
		u.setUserContact(user.getUserContact());
	}
}