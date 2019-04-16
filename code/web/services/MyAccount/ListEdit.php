<?php

require_once ROOT_DIR . '/Action.php';

require_once ROOT_DIR . '/sys/LocalEnrichment/UserList.php';
class MyAccount_ListEdit extends Action
{
	private $user;

	function __construct()
	{
		$this->user = UserAccount::getLoggedInUser();
	}

	function launch() {
		global $interface;

		// Depending on context, we may get the record ID that initiated the "add
		// list" action in a couple of different places -- make sure we check all
		// necessary options before giving up!
		if (!isset($_GET['id']) && isset($_REQUEST['recordId'])) {
			$_GET['id'] = $_REQUEST['recordId'];
		}
		$interface->assign('recordId', isset($_GET['id']) ? $_GET['id'] : false);
		$interface->assign('source', isset($_GET['source']) ? $_GET['source'] : false);

		// Check if user is logged in
		if (!$this->user) {
			if (isset($_GET['lightbox'])) {
				$interface->assign('title', $_GET['message']);
				$interface->assign('message', 'You must be logged in first');
				return $interface->fetch('AJAX/login.tpl');
			} else {
				require_once ROOT_DIR . '/services/MyAccount/Login.php';
				$loginAction = new MyAccount_Login();
				$loginAction->launch();
			}
			exit();
		}

		// Display Page
		if (isset($_GET['lightbox'])) {
			$interface->assign('title', translate('Create new list'));
			echo $interface->fetch('MyResearch/list-form.tpl');
		} else {
			if (isset($_REQUEST['submit'])) {
				$result = $this->addList();
				if ($result instanceof AspenError) {
					$interface->assign('listError', $result->getMessage());
				} else {
					if (!empty($_REQUEST['recordId'])) {
						$url = '../Record/' . urlencode($_REQUEST['recordId']) . '/Save';
					} else {
						$url = 'Home';
					}
					header('Location: ' . $url);
					die();
				}
			}
			$interface->setPageTitle('Create a List');
			$interface->assign('subTemplate', 'list-form.tpl');
			$interface->setTemplate('view-alt.tpl');
			$interface->display('layout.tpl');
		}
	}

	function addList() {
		if ($this->user) {
			if (strlen(trim($_REQUEST['title'])) == 0) {
				return new AspenError('list_edit_name_required');
			}
			$list = new UserList();
			$list->title = strip_tags($_REQUEST['title']);
			$list->description = strip_tags($_REQUEST['desc']);
			$list->public = $_REQUEST['public'];
			$list->user_id = $this->user->id;
			$list->insert();
			$list->find();
			return $list->id;
		}else{
			return false;
		}
	}
}
