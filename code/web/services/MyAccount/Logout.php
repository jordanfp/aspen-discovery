<?php

require_once ROOT_DIR . '/Action.php';

class Logout extends Action {

	public function launch() {
		global $configArray;

		UserAccount::logout();

		header('Location: ' . $configArray['Site']['path'] . '/');
	}
}
