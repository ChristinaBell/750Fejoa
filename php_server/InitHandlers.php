<?php

include_once 'ContactRequestHandler.php';
include_once 'MessageHandler.php';
include_once 'SyncHandler.php';
include_once 'WatchBranchHandler.php';

include_once 'JSONPublishBranchHandler.php';
include_once 'JSONAuthHandler.php';


function initSyncHandlers($XMLHandler) {
	$userDir = Session::get()->getAccountUser();
	if (!file_exists ($userDir))
		mkdir($userDir);
	$database = new GitDatabase($userDir."/.git");

	// pull
	$pullIqGetHandler = new InIqStanzaHandler(IqType::$kGet);
	$pullHandler = new SyncPullStanzaHandler($XMLHandler->getInStream(), $database);
	$pullIqGetHandler->addChild($pullHandler);
	$XMLHandler->addHandler($pullIqGetHandler);

	// push
	$pushIqGetHandler = new InIqStanzaHandler(IqType::$kSet);
	$pushHandler = new SyncPushStanzaHandler($XMLHandler->getInStream(), $database);
	$pushIqGetHandler->addChild($pushHandler);
	$XMLHandler->addHandler($pushIqGetHandler);
}

function initWatchBranchesStanzaHandler($XMLHandler) {
	$iqHandler = new InIqStanzaHandler(IqType::$kGet);
	$handler = new WatchBranchesStanzaHandler($XMLHandler->getInStream());
	$iqHandler->addChild($handler);
	$XMLHandler->addHandler($iqHandler);
}


function initContactRequestStanzaHandler($XMLHandler) {
	$iqHandler = new InIqStanzaHandler(IqType::$kGet);
	$handler = new ContactRequestStanzaHandler($XMLHandler->getInStream());
	$iqHandler->addChild($handler);
	$XMLHandler->addHandler($iqHandler);
}

// auth
function initAuthHandlers($JSONDispatcher) {
	$JSONDispatcher->addHandler(new JSONAuthHandler());
	$JSONDispatcher->addHandler(new JSONAuthSignedHandler());
	$JSONDispatcher->addHandler(new JSONLogoutHandler());
}

// auth
function initPublishBranchHandlers($JSONDispatcher) {
	$JSONDispatcher->addHandler(new JSONInitPublishBranchHandler());
	$JSONDispatcher->addHandler(new JSONLoginPublishBranchHandler());
}

function initMessageHandlers($XMLHandler) {
	$iqHandler = new InIqStanzaHandler(IqType::$kSet);
	$handler = new MessageStanzaHandler($XMLHandler->getInStream());
	$iqHandler->addChild($handler);
	$XMLHandler->addHandler($iqHandler);	
}


class InitHandlers {
	static public function initPrivateHandlers($XMLHandler, $JSONDispatcher) {
		initSyncHandlers($XMLHandler);
		initMessageHandlers($XMLHandler);
		initWatchBranchesStanzaHandler($XMLHandler);
		initContactRequestStanzaHandler($XMLHandler);

		initAuthHandlers($JSONDispatcher);
		initPublishBranchHandlers($JSONDispatcher);
	}

	static public function initPublicHandlers($XMLHandler, $JSONDispatcher) {
		initMessageHandlers($XMLHandler);
		initContactRequestStanzaHandler($XMLHandler);

		initAuthHandlers($JSONDispatcher);
		initPublishBranchHandlers($JSONDispatcher);
	}
}

?>