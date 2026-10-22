package me.dcnick3.baam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import me.dcnick3.baam.api.Account
import me.dcnick3.baam.ui.theme.BaamTheme
import me.dcnick3.baam.viewmodel.ApiViewModel

/**
 * Shows which accounts the scanned codes are submitted for, and allows changing the selection.
 */
@Composable
fun AccountBar(vm: ApiViewModel) {
    val accounts by vm.accounts.collectAsState()
    val selected by vm.selectedAccounts.collectAsState()

    AccountBar(
        accounts = accounts,
        selected = selected,
        onToggle = { vm.toggleAccount(it.id) },
        onAdd = vm::startLogin,
        onRename = { account, name -> vm.renameAccount(account.id, name) },
        onLogOut = { vm.removeAccount(it.id) },
    )
}

@Composable
private fun AccountBar(
    accounts: List<Account>,
    selected: List<Account>,
    onToggle: (Account) -> Unit,
    onAdd: () -> Unit,
    onRename: (Account, String) -> Unit,
    onLogOut: (Account) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(null as Account?) }
    var loggingOut by remember { mutableStateOf(null as Account?) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { menuOpen = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Group,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (selected.size) {
                        0, 1 -> "Scanning for"
                        else -> "Scanning for ${selected.size} accounts"
                    },
                    fontSize = 3.em,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    selected.joinToString { it.name }.ifEmpty { "No account" },
                    fontSize = 4.5.em,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "Choose accounts",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (account in accounts) {
                val isSelected = selected.any { it.id == account.id }
                // the menu stays open, so that several accounts can be (de)selected in one go
                DropdownMenuItem(
                    text = { Text(account.name) },
                    leadingIcon = {
                        Checkbox(checked = isSelected, onCheckedChange = { onToggle(account) })
                    },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = {
                                menuOpen = false
                                renaming = account
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename ${account.name}")
                            }
                            IconButton(onClick = {
                                menuOpen = false
                                loggingOut = account
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = "Log out ${account.name}"
                                )
                            }
                        }
                    },
                    onClick = { onToggle(account) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add account") },
                leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onAdd()
                },
            )
        }
    }

    renaming?.let { account ->
        RenameDialog(
            account = account,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRename(account, name)
            },
        )
    }

    loggingOut?.let { account ->
        AlertDialog(
            onDismissRequest = { loggingOut = null },
            title = { Text("Log out?") },
            text = { Text("${account.name} will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    loggingOut = null
                    onLogOut(account)
                }) { Text("Log out") }
            },
            dismissButton = {
                TextButton(onClick = { loggingOut = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RenameDialog(account: Account, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(account.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename account") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun AccountBarPreview() {
    val accounts = listOf(
        Account("1", "i.ivanov@innopolis.university", ""),
        Account("2", "Account 2", ""),
        Account("3", "Account 3", ""),
    )
    BaamTheme {
        AccountBar(accounts, accounts.take(2), {}, {}, { _, _ -> }, {})
    }
}
