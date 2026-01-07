package com.example.studentmanagementapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// --- DATA LAYER ---

@Entity(tableName = "students")
data class Student(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val email: String,
    val regNumber: String,
    val age: Int,
    val course: String
)

@Dao
interface StudentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: Student)

    @Query("SELECT * FROM students ORDER BY id DESC")
    fun getAllStudents(): Flow<List<Student>>

    @Update
    suspend fun updateStudent(student: Student)

    @Delete
    suspend fun deleteStudent(student: Student)
}

@Database(entities = [Student::class], version = 1, exportSchema = false)
abstract class StudentDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
}

class StudentRepository(private val dao: StudentDao) {
    fun getAllStudents(): Flow<List<Student>> = dao.getAllStudents()
    suspend fun insert(student: Student) = dao.insertStudent(student)
    suspend fun update(student: Student) = dao.updateStudent(student)
    suspend fun delete(student: Student) = dao.deleteStudent(student)
}

// --- VIEWMODEL ---

class StudentViewModel(private val repo: StudentRepository) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val students: Flow<List<Student>> = combine(
        repo.getAllStudents(),
        _searchQuery
    ) { students, query ->
        if (query.isBlank()) students
        else students.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.course.contains(query, ignoreCase = true) ||
                    it.regNumber.contains(query, ignoreCase = true)
        }
    }

    fun onSearchQueryChange(newQuery: String) { _searchQuery.value = newQuery }

    fun addStudent(name: String, email: String, reg: String, age: Int, course: String) {
        viewModelScope.launch { repo.insert(Student(0, name, email, reg, age, course)) }
    }

    fun updateStudent(student: Student) {
        viewModelScope.launch { repo.update(student) }
    }

    fun deleteStudent(student: Student) {
        viewModelScope.launch { repo.delete(student) }
    }
}

class StudentViewModelFactory(private val repo: StudentRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return StudentViewModel(repo) as T
    }
}

// --- MAIN ACTIVITY ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val db = Room.databaseBuilder(applicationContext, StudentDatabase::class.java, "student_db").build()
        val repo = StudentRepository(db.studentDao())
        val factory = StudentViewModelFactory(repo)
        val viewModel = ViewModelProvider(this, factory)[StudentViewModel::class.java]

        setContentView(R.layout.activity_main)
        findViewById<ComposeView>(R.id.compose_view).setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Black, onPrimary = Color.White)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    StudentListScreen(viewModel)
                }
            }
        }
    }
}

// --- UI COMPONENTS ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentListScreen(viewModel: StudentViewModel) {
    val students by viewModel.students.collectAsState(initial = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsState()
    var isSearchActive by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingStudent by remember { mutableStateOf<Student?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.onSearchQueryChange(it) },
                            placeholder = { Text("Search students...") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, 
                                unfocusedContainerColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                    } else {
                        Text("STUDENTS", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        isSearchActive = !isSearchActive 
                        if (!isSearchActive) viewModel.onSearchQueryChange("")
                    }) {
                        Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Add")
            }
        }
    ) { padding ->
        if (students.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No students found")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding), 
                contentPadding = PaddingValues(16.dp), 
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(students) { student ->
                    StudentRow(student, onEdit = { editingStudent = student }, onDelete = { viewModel.deleteStudent(student) })
                }
            }
        }
    }

    if (showAddDialog) {
        StudentFormDialog("Add Student", onDismiss = { showAddDialog = false }) { n, e, r, a, c ->
            viewModel.addStudent(n, e, r, a, c)
            showAddDialog = false
        }
    }

    if (editingStudent != null) {
        StudentFormDialog("Update Student", student = editingStudent, onDismiss = { editingStudent = null }) { n, e, r, a, c ->
            viewModel.updateStudent(editingStudent!!.copy(name = n, email = e, regNumber = r, age = a, course = c))
            editingStudent = null
        }
    }
}

@Composable
fun StudentFormDialog(
    title: String, 
    student: Student? = null, 
    onDismiss: () -> Unit, 
    onConfirm: (String, String, String, Int, String) -> Unit
) {
    var name by remember { mutableStateOf(student?.name ?: "") }
    var email by remember { mutableStateOf(student?.email ?: "") }
    var reg by remember { mutableStateOf(student?.regNumber ?: "") }
    var ageText by remember { mutableStateOf(student?.age?.toString() ?: "") }
    var course by remember { mutableStateOf(student?.course ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") })
                OutlinedTextField(value = reg, onValueChange = { reg = it }, label = { Text("Reg No") })
                OutlinedTextField(
                    value = ageText, 
                    onValueChange = { ageText = it }, 
                    label = { Text("Age") }, 
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(value = course, onValueChange = { course = it }, label = { Text("Course") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { onConfirm(name, email, reg, ageText.toIntOrNull() ?: 0, course) }, 
                        enabled = name.isNotBlank()
                    ) { 
                        Text(if (student == null) "Save" else "Update") 
                    }
                }
            }
        }
    }
}

@Composable
fun StudentRow(student: Student, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(student.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Course: ${student.course}", style = MaterialTheme.typography.bodyMedium)
                Text("Reg: ${student.regNumber}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = Color.Red) }
        }
    }
}
