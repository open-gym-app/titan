package com.titan.feature.progression.photos

import com.titan.core.domain.model.ProgressPhoto
import com.titan.core.domain.repository.PhotoSource
import com.titan.core.domain.repository.ProgressPhotoRepository
import com.titan.feature.progression.stats.FixedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** What the photo gallery makes of the store behind it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressPhotosViewModelTest {

    private val store = FakePhotoStore()
    private lateinit var viewModel: ProgressPhotosViewModel

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.collect(): () -> ProgressPhotosUiState {
        viewModel = ProgressPhotosViewModel(repository = store, clock = FixedClock(NOW))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { } }
        return { viewModel.uiState.value }
    }

    private val bytes = PhotoSource { ByteArray(1).inputStream() }

    @Test
    fun `a picked photo is stored, dated now unless it says otherwise, and its scratch file cleaned up`() =
        runTest {
            val state = collect()
            var cleaned = false

            viewModel.onEvent(ProgressPhotosEvent.PhotoPicked(bytes, cleanup = { cleaned = true }))

            assertEquals(listOf(NOW), state().photos.map(ProgressPhoto::takenAt))
            assertTrue(cleaned)
            assertFalse(state().isAdding)
        }

    @Test
    fun `a photo the store refuses says so, adds nothing, and is still cleaned up`() =
        runTest {
            store.addFailure = IOException("not an image")
            val state = collect()
            var cleaned = false

            viewModel.onEvent(ProgressPhotosEvent.PhotoPicked(bytes, cleanup = { cleaned = true }))

            assertTrue(state().photos.isEmpty())
            assertEquals(ProgressPhotosMessage.ADD_FAILED, state().message)
            assertTrue(cleaned)
        }

    @Test
    fun `a camera that gives nothing back is said, not silent`() =
        runTest {
            val state = collect()

            viewModel.onEvent(ProgressPhotosEvent.PhotoUnavailable)

            assertEquals(ProgressPhotosMessage.ADD_FAILED, state().message)
        }

    @Test
    fun `tapping a photo opens it`() =
        runTest {
            store.seed(1, takenAt = 1_000)
            val state = collect()

            viewModel.onEvent(ProgressPhotosEvent.PhotoTapped(1))

            assertEquals(1L, state().viewing?.id)
        }

    @Test
    fun `comparing pairs the photo it started from with the next one tapped, older first`() =
        runTest {
            store.seed(1, takenAt = 3_000)
            store.seed(2, takenAt = 1_000)
            val state = collect()

            viewModel.onEvent(ProgressPhotosEvent.PhotoTapped(1))
            viewModel.onEvent(ProgressPhotosEvent.CompareStarted(1))
            assertNull(state().viewing)
            assertEquals(1L, state().comparingFrom?.id)

            viewModel.onEvent(ProgressPhotosEvent.PhotoTapped(2))

            assertEquals(2L to 1L, state().comparison?.let { it.first.id to it.second.id })
            assertNull(state().comparingFrom)
        }

    @Test
    fun `tapping the photo a comparison started from does nothing`() =
        runTest {
            store.seed(1, takenAt = 1_000)
            val state = collect()

            viewModel.onEvent(ProgressPhotosEvent.CompareStarted(1))
            viewModel.onEvent(ProgressPhotosEvent.PhotoTapped(1))

            assertNull(state().comparison)
            assertEquals(1L, state().comparingFrom?.id)
        }

    @Test
    fun `a photo is deleted only once the user confirms`() =
        runTest {
            store.seed(1, takenAt = 1_000)
            val state = collect()

            viewModel.onEvent(ProgressPhotosEvent.DeleteRequested(1))
            assertEquals(1, state().photos.size)
            assertEquals(1L, state().confirmingDelete?.id)

            viewModel.onEvent(ProgressPhotosEvent.DeleteConfirmed)

            assertTrue(state().photos.isEmpty())
            assertNull(state().confirmingDelete)
        }

    @Test
    fun `a viewer over a photo that disappears closes rather than drawing nothing`() =
        runTest {
            store.seed(1, takenAt = 1_000)
            val state = collect()
            viewModel.onEvent(ProgressPhotosEvent.PhotoTapped(1))

            store.delete(1)

            assertNull(state().viewing)
        }

    @Test
    fun `a gallery with no photos is empty, and one still loading is not`() =
        runTest {
            assertFalse(ProgressPhotosUiState().isEmpty)
            val state = collect()

            assertTrue(state().isEmpty)
        }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

/** The photo store, in memory. */
private class FakePhotoStore : ProgressPhotoRepository {

    private val photos = MutableStateFlow<List<ProgressPhoto>>(emptyList())
    private var nextId = 100L

    var addFailure: Exception? = null

    fun seed(
        id: Long,
        takenAt: Long,
    ) {
        photos.value += ProgressPhoto(id = id, takenAt = takenAt, path = "/photos/$id.jpg", createdAt = takenAt)
    }

    override fun getAll(): Flow<List<ProgressPhoto>> =
        photos.map { all -> all.sortedByDescending(ProgressPhoto::takenAt) }

    override suspend fun add(
        source: PhotoSource,
        takenAt: Long,
        preferImageTime: Boolean,
    ): Long {
        addFailure?.let { throw it }
        val id = nextId++
        photos.value += ProgressPhoto(id = id, takenAt = takenAt, path = "/photos/$id.jpg", createdAt = takenAt)
        return id
    }

    override suspend fun delete(id: Long) {
        photos.value = photos.value.filterNot { it.id == id }
    }
}
