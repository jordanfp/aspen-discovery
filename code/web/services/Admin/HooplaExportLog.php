<?php

require_once ROOT_DIR . '/Action.php';
require_once ROOT_DIR . '/services/Admin/Admin.php';
require_once ROOT_DIR . '/sys/Pager.php';

class HooplaExportLog extends Admin_Admin
{
	function launch()
	{
		global $interface;
		global $configArray;

		$logEntries = array();
		$logEntry = new HooplaExportLogEntry();
		$total = $logEntry->count();
		$logEntry = new HooplaExportLogEntry();
		$logEntry->orderBy('startTime DESC');
		$page = isset($_REQUEST['page']) ? $_REQUEST['page'] : 1;
		$pagesize = isset($_REQUEST['pagesize']) ? $_REQUEST['pagesize'] : 30; // to adjust number of items listed on a page
		$interface->assign('recordsPerPage', $pagesize);
		$interface->assign('page', $page);
		$logEntry->limit(($page - 1) * $pagesize, $pagesize);
		$logEntry->find();
		while ($logEntry->fetch()){
			$logEntries[] = clone($logEntry);
		}
		$interface->assign('logEntries', $logEntries);

		$options = array('totalItems' => $total,
		                 'fileName'   => $configArray['Site']['path'].'/Admin/HooplaExportLog?page=%d'. (empty($_REQUEST['pagesize']) ? '' : '&pagesize=' . $_REQUEST['pagesize']),
		                 'perPage'    => $pagesize,
		);
		$pager = new Pager($options);
		$interface->assign('pageLinks', $pager->getLinks());

		$this->display('hooplaExportLog.tpl', 'Hoopla Export Log');
	}

	function getAllowableRoles(){
		return array('opacAdmin', 'libraryAdmin', 'cataloging');
	}
}
